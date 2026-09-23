package com.genvict.dssad.cloud.mqtt.core;

import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 入站报文队列：隔离「MQTT 回调线程」与「业务处理线程」。
 *
 * <p>这是本平台最重要的稳定性设计之一。Paho 的 {@code messageArrived} 回调运行在
 * 客户端内部线程上，该线程同时负责<b>心跳保活与 ACK 发送</b>。如果直接在回调里执行业务
 * （数据库写入、HTTP 调用），一旦数据库慢查询（秒级）就会：
 * <ol>
 *   <li>阻塞心跳 → Broker 判定超时 → 断开连接 → 触发重连风暴；</li>
 *   <li>阻塞 QoS 1 的 PUBACK 发送 → 对端认为未送达 → 触发对端重发 → 报文堆积。</li>
 * </ol>
 *
 * <p>因此回调线程只做「入队」（微秒级），业务处理由 {@link MqttInboundWorker} 的独立线程池消费。
 * 队列有界：满时丢弃<b>最旧</b>的一条并计数告警 —— 积压说明处理能力不足，
 * 此时保留最新数据比保留全部历史更有价值（历史数据可从车端重发补齐）。
 */
@Component
public class InboundMessageQueue {

    /** 默认队列容量：按峰值 10 倍冗余估算（千车 1Hz ≈ 1000 条/秒，留 10 秒缓冲）。 */
    private static final int CAPACITY = 20_000;

    private final BlockingQueue<MqttEnvelope> queue = new ArrayBlockingQueue<>(CAPACITY);
    private final AtomicLong dropped = new AtomicLong();

    /**
     * 入队（非阻塞）。
     *
     * @return 成功入队返回 {@code true}；队列满时丢弃最旧报文并返回 {@code false}
     */
    public boolean offer(MqttEnvelope envelope) {
        if (queue.offer(envelope)) {
            return true;
        }
        // 队列满：丢弃最旧的一条，为最新报文腾位
        queue.poll();
        dropped.incrementAndGet();
        queue.offer(envelope);
        return false;
    }

    /** 阻塞等待下一条（消费者线程使用）。 */
    public MqttEnvelope take() throws InterruptedException {
        return queue.take();
    }

    /** 当前积压条数（监控指标）。 */
    public int size() {
        return queue.size();
    }

    /** 因队列满被丢弃的报文数（持续增长说明消费能力不足）。 */
    public long droppedCount() {
        return dropped.get();
    }

    /** 清空（停机/测试用）。 */
    public void clear() {
        queue.clear();
    }
}
