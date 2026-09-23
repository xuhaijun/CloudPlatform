package com.genvict.dssad.cloud.mqtt.core;

import com.genvict.dssad.cloud.common.util.TimeUtils;
import com.genvict.dssad.cloud.mqtt.model.UploadPriority;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 重发与补传调度器：让「未确认报文」与「离线积压」自动收敛。
 *
 * <p>每秒执行一次，做两件事：
 * <ol>
 *   <li><b>重发到期未确认的报文</b>：从 {@link PendingAckRegistry} 取出到期的登记项重新发布，
 *       并按文档 5.1.4.1（5s / 3 次）与 8.1（3s / 5 次）的规则推进计数；</li>
 *   <li><b>补传离线队列</b>：连接恢复后以受控速率（每秒一批）倾倒积压，
 *       避免重连瞬间几千条报文同时发出触发对端限流（文档 9.1：10 条/秒·车）。</li>
 * </ol>
 *
 * <p>独立于 {@link MqttInboundWorker}（入站）与业务请求线程，确保重发节奏不受业务负载影响。
 * 未连接时直接跳过：重连后的首轮补传由 {@link PahoMqttClientManager#flushOffline(int)} 触发。
 */
@Slf4j
@Component
public class MqttAckRetryScheduler {

    /** 每轮补传条数（≈ 20 条/秒，兼顾效率与限流）。 */
    private static final int FLUSH_PER_TICK = 20;

    private final PendingAckRegistry pendingAckRegistry;
    private final MqttBridge mqttBridge;
    private final OfflineMessageQueue offlineQueue;

    public MqttAckRetryScheduler(PendingAckRegistry pendingAckRegistry,
                                 MqttBridge mqttBridge,
                                 OfflineMessageQueue offlineQueue) {
        this.pendingAckRegistry = pendingAckRegistry;
        this.mqttBridge = mqttBridge;
        this.offlineQueue = offlineQueue;
    }

    /** 每 1 秒扫描一次。 */
    @Scheduled(fixedDelay = 1_000L)
    public void tick() {
        if (!mqttBridge.isConnected()) {
            return;
        }
        resendExpired();
        flushOfflineIfNeeded();
    }

    /** 重发到期未确认报文。 */
    private void resendExpired() {
        List<PendingAckRegistry.Pending> dueItems = pendingAckRegistry.due(TimeUtils.nowMillis());
        for (PendingAckRegistry.Pending pending : dueItems) {
            if (!mqttBridge.publish(pending.topic(), pending.payloadJson(), pending.qos())) {
                // 发布失败（连接瞬断等）：退回离线队列，不再消耗重发次数
                offlineQueue.offer(new OfflineMessageQueue.QueuedMessage(
                        pending.topic(), pending.payloadJson(), pending.qos(),
                        pending.priority() == null ? UploadPriority.BASE : pending.priority(),
                        TimeUtils.nowMillis()));
                continue;
            }
            boolean advanced = pendingAckRegistry.advance(pending);
            log.warn("[重发] msgId={} topic={} 第 {} 次重发，策略={} {}",
                    pending.msgId(), pending.topic(), pending.attempts() + 1, pending.kind(),
                    advanced ? "继续等待确认" : "已达上限，放弃");
        }
    }

    /** 补传离线积压。 */
    private void flushOfflineIfNeeded() {
        if (offlineQueue.size() == 0) {
            return;
        }
        mqttBridge.flushOffline(FLUSH_PER_TICK);
    }

    /** 供监控端点读取：在册待确认数。 */
    public int pendingCount() {
        return pendingAckRegistry.size();
    }
}
