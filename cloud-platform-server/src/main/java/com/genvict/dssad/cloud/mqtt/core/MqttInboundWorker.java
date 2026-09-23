package com.genvict.dssad.cloud.mqtt.core;

import com.genvict.dssad.cloud.config.AppProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 入站报文消费器：把 {@link InboundMessageQueue} 中的报文分发给 {@link MqttMessageRouter}。
 *
 * <p>独立线程池的意义（也是本平台可用的前提）：
 * <ul>
 *   <li><b>保护 MQTT 心跳</b>：业务处理（数据库写入、HTTP 调用）可能耗时几十毫秒到数秒，
 *       若在 Paho 回调线程执行，会阻塞心跳发送导致 Broker 判定断线，进而触发重连风暴；</li>
 *   <li><b>并行处理</b>：默认 4 个消费线程，可并行处理不同车辆的报文；
 *       同一车辆的报文虽有顺序依赖（1Hz 采样点），但轨迹表以 {@code ts} 排序，
 *       写入顺序不影响查询结果的正确性；</li>
 *   <li><b>故障隔离</b>：单条报文处理抛出的任何异常都被捕获，消费线程不会退出。</li>
 * </ul>
 *
 * <p>优雅停机：{@link #stop()} 先置 {@code running=false} 并中断线程，
 * 再给 5 秒等待时间；未能及时处理完的队列内容会记录日志（可通过离线队列/车端重发补齐）。
 *
 * <h2>消费线程为什么用虚拟线程（Java 21）</h2>
 * 消费循环是<b>纯阻塞型</b>：绝大部分时间阻塞在 {@code queue.take()} 或 JDBC 往返上，
 * CPU 占用极低。这与虚拟线程的设计目标完全吻合：
 * <ul>
 *   <li>平台线程的栈是<b>按线程预留</b>的（默认 1MB），4 个消费者看似无碍，
 *       但要靠「加大线程数」提升吞吐时立刻撞墙 —— 64 个消费者 = 64MB 常驻栈内存；</li>
 *   <li>虚拟线程在阻塞时把载体（carrier）线程还给调度器，栈按需增长、堆上存储，
 *       单机可轻松跑数万个；</li>
 *   <li>收益是「<b>把 {@code consumer-threads} 变成可以放心调大的旋钮</b>」：
 *       车端报文处理里混着数据库写和 HTTPS 调用，串行时单条几十毫秒，
 *       并发度不足会直接体现为队列积压（监控端点 {@code /api/v1/monitor/mqtt} 可见）。</li>
 * </ul>
 *
 * <p>可通过 {@code dssad.mqtt.virtual-threads=false} 退回平台线程 ——
 * 便于在排查 pinning 问题时做 A/B 对比
 * （JFR 事件 {@code jdk.VirtualThreadPinned}，或启动加 {@code -Djdk.tracePinnedThreads=full}）。
 */
@Slf4j
@Component
public class MqttInboundWorker {

    private final InboundMessageQueue queue;
    private final MqttMessageRouter router;
    private final int threadCount;

    /** 是否用虚拟线程承载消费循环，默认开启；见类注释。 */
    @org.springframework.beans.factory.annotation.Value("${dssad.mqtt.virtual-threads:true}")
    private boolean virtualThreads = true;

    private final AtomicLong processedCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();

    private volatile boolean running = true;
    private Thread[] workers = new Thread[0];

    public MqttInboundWorker(InboundMessageQueue queue, MqttMessageRouter router, AppProperties properties) {
        this.queue = queue;
        this.router = router;
        this.threadCount = Math.max(1, properties.mqtt().consumerThreads());
    }

    /**
     * 启动消费线程。
     *
     * <p>直接使用裸线程而不是 {@link java.util.concurrent.ExecutorService}：
     * 这里是「固定数量的长驻阻塞循环」，需要精确控制线程名与优雅停机语义，
     * 线程池的队列/拒绝策略等机制在此场景没有收益，反而增加理解成本。
     *
     * <p>虚拟线程恒为守护线程，因此不能调用 {@code setDaemon(true)}
     * （会抛 {@link UnsupportedOperationException}），这也与运行语义一致：
     * 进程退出时消费者随之中止，由车端重发补齐。
     */
    @PostConstruct
    void start() {
        workers = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            int index = i;
            String name = "mqtt-consumer-" + index;
            if (virtualThreads) {
                workers[index] = Thread.ofVirtual().name(name).unstarted(this::consumeLoop);
            } else {
                Thread platform = new Thread(this::consumeLoop, name);
                platform.setDaemon(true);
                workers[index] = platform;
            }
            workers[index].start();
        }
        log.info("[入站] 已启动 {} 个报文消费线程（{}）", threadCount,
                virtualThreads ? "虚拟线程" : "平台线程");
    }

    @PreDestroy
    void stop() {
        running = false;
        for (Thread worker : workers) {
            if (worker != null) {
                worker.interrupt();
            }
        }
        int remaining = queue.size();
        if (remaining > 0) {
            log.warn("[入站] 停机时仍有 {} 条报文未处理，已丢弃（车端将按重发策略补投）", remaining);
        }
    }

    /** 消费循环：阻塞取队列，异常绝不外泄。 */
    private void consumeLoop() {
        while (running) {
            MqttEnvelope envelope;
            try {
                envelope = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                router.route(envelope);
                processedCount.incrementAndGet();
            } catch (Throwable t) {
                // Throwable 而非 Exception：路由内部若因 OOM 等 Error 抛出，也不能让消费线程死掉
                failedCount.incrementAndGet();
                log.error("[入站] 报文处理出现未捕获异常 topic={}", envelope.topic(), t);
            }
        }
    }

    /** 已处理报文数。 */
    public long processedCount() {
        return processedCount.get();
    }

    /** 处理失败数。 */
    public long failedCount() {
        return failedCount.get();
    }

    /** 是否运行中。 */
    public boolean isRunning() {
        return running;
    }

    /** 消费线程数。 */
    public int threadCount() {
        return threadCount;
    }

    /** 消费线程是否为虚拟线程（供监控端点展示，见类注释「为什么用虚拟线程」）。 */
    public boolean usesVirtualThreads() {
        return virtualThreads;
    }

    /** 便于测试：等待队列清空（最多等待 timeoutMillis）。 */
    boolean awaitDrained(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline && queue.size() > 0) {
            try {
                TimeUnit.MILLISECONDS.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return queue.size() == 0;
    }
}
