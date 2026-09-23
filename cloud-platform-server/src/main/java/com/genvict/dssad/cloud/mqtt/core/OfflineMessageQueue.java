package com.genvict.dssad.cloud.mqtt.core;

import com.genvict.dssad.cloud.config.AppProperties;
import com.genvict.dssad.cloud.mqtt.model.UploadPriority;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 离线消息队列。
 *
 * <p>解决文档 8.2「本地缓存：离线期间数据缓存在本地，重连后上传」与
 * 8.4「重连后优先上传：事件数据 &gt; 基础数据 &gt; 状态数据」。
 *
 * <p>实现要点：
 * <ul>
 *   <li><b>有界 + 按优先级取舍</b>：队列满时的判定规则是
 *       「新消息是否比队列中<b>最差</b>的那条更重要」——
 *       <ul>
 *         <li>更重要（或同级）：淘汰最差的那条，收下新消息；同级淘汰等价于 FIFO 轮转，
 *             对 1Hz 状态数据而言保留「最新位置」比保留最旧的更有价值；</li>
 *         <li>不如最差的重要：直接丢弃新消息，保护队列里已有的高价值数据。</li>
 *       </ul>
 *       这条规则保证「事故/故障事件绝不会被状态报文挤掉」，对应文档 8.4 的优先级要求。</li>
 *   <li><b>同优先级 FIFO</b>：按优先级序号 + 入队时间排序，保证同一车辆的状态数据时序不乱。</li>
 *   <li><b>单锁 + 二叉堆</b>：相比 {@code PriorityBlockingQueue}，此处需要「容量满时淘汰堆内
 *       最低优先级元素」的能力，故显式加锁管理；离线补传不是高频路径，锁竞争可忽略。</li>
 * </ul>
 *
 * <p>限制说明：本实现为<b>进程内队列</b>。若需跨实例/跨重启不丢，可将 {@link QueuedMessage}
 * 序列化后写入 Redis List 或本地磁盘 WAL（详见详细设计说明书「高可用增强」章节）。
 */
@Slf4j
@Component
public class OfflineMessageQueue {

    /** 容量下限：配置过小会让队列失去缓存意义，故不低于 100。 */
    private static final int MIN_CAPACITY = 100;

    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final PriorityQueue<QueuedMessage> queue;

    /** 因优先级不足而被拒收的<b>新</b>消息数（队列满且新消息最不重要）。 */
    private long droppedLowPriority;

    /** 因容量压力被淘汰的<b>已有</b>消息数（队列满且新消息更值得保留）。 */
    private long droppedByPressure;

    public OfflineMessageQueue(AppProperties properties) {
        this.capacity = Math.max(MIN_CAPACITY, properties.mqtt().offlineCacheSize());
        // 容量 + 时间双重比较：优先级高的先出队，同优先级按入队时间先入先出
        this.queue = new PriorityQueue<>(Comparator
                .comparingInt((QueuedMessage m) -> m.priority().order())
                .thenComparingLong(QueuedMessage::queuedAt));
    }

    /**
     * 入队。
     *
     * <p>容量判定以「队列中<b>最差</b>的那条」为参照物：新消息比它更重要（或同级）就挤掉它，
     * 否则宁可丢弃新消息也不动已有数据。注意 {@link UploadPriority} 的 {@code order()}
     * <b>越小优先级越高</b>，因此「更重要」对应的是 {@code order() 更小}，
     * 比较符方向写反会导致事故数据被 1Hz 状态报文挤掉——这类错误在联调前不会被发现，
     * 故由 {@code MqttReliabilityTest.OfflineQueue} 逐条钉死。
     *
     * @return {@code true} 表示本次新消息因优先级最低被<b>拒收</b>（调用方可据此计数并告警）；
     *         {@code false} 表示消息已入队（可能同时淘汰了一条更低优先级的旧消息）
     */
    public boolean offer(QueuedMessage message) {
        if (message == null) {
            return false;
        }
        lock.lock();
        try {
            if (queue.size() >= capacity) {
                QueuedMessage worst = findLowestPriority();
                if (worst == null) {
                    queue.offer(message);
                    return false;
                }
                if (message.priority().order() <= worst.priority().order()) {
                    // 新消息更重要或同级：淘汰最差元素，收下新消息
                    queue.remove(worst);
                    droppedByPressure++;
                    log.warn("[离线队列] 容量满({})，淘汰低优先级消息 topic={} priority={}",
                            capacity, worst.topic(), worst.priority());
                } else {
                    // 新消息优先级更低：直接丢弃新消息，保护队列中的高优先级数据
                    droppedLowPriority++;
                    log.warn("[离线队列] 容量满({})，本次消息优先级最低被丢弃 topic={} priority={}",
                            capacity, message.topic(), message.priority());
                    return true;
                }
            }
            queue.offer(message);
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 按优先级批量出队（重连后调用）。
     *
     * @param maxCount 单次最大补传条数，防止重连瞬间打爆限流（文档 9.1：10 条/秒·车）
     */
    public List<QueuedMessage> drain(int maxCount) {
        List<QueuedMessage> result = new ArrayList<>(Math.min(maxCount, capacity));
        lock.lock();
        try {
            while (result.size() < maxCount && !queue.isEmpty()) {
                result.add(queue.poll());
            }
        } finally {
            lock.unlock();
        }
        return result;
    }

    /** 当前积压条数。 */
    public int size() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    /** 因容量压力被淘汰的条数。 */
    public long droppedByPressure() {
        lock.lock();
        try {
            return droppedByPressure;
        } finally {
            lock.unlock();
        }
    }

    /** 因自身优先级过低被丢弃的条数。 */
    public long droppedLowPriority() {
        lock.lock();
        try {
            return droppedLowPriority;
        } finally {
            lock.unlock();
        }
    }

    /** 清空队列（测试与手工运维用）。 */
    public void clear() {
        lock.lock();
        try {
            queue.clear();
        } finally {
            lock.unlock();
        }
    }

    @PreDestroy
    void onShutdown() {
        int remaining = size();
        if (remaining > 0) {
            log.warn("[离线队列] 服务关闭，仍有 {} 条消息未补传（进程内队列，将丢失）", remaining);
        }
    }

    private QueuedMessage findLowestPriority() {
        QueuedMessage worst = null;
        for (QueuedMessage m : queue) {
            if (worst == null || m.priority().order() > worst.priority().order()) {
                worst = m;
            }
        }
        return worst;
    }

    /**
     * 待补传消息。
     *
     * @param topic       目标 Topic
     * @param payloadJson 已序列化的报文（入队前序列化一次，避免补传时重复序列化）
     * @param qos         QoS
     * @param priority    补传优先级
     * @param queuedAt    入队时间
     */
    public record QueuedMessage(String topic, String payloadJson, int qos,
                                UploadPriority priority, long queuedAt) {
    }
}
