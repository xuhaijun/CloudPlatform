package com.genvict.dssad.cloud.mqtt.core;

import com.genvict.dssad.cloud.common.util.JsonUtils;
import com.genvict.dssad.cloud.common.util.TimeUtils;
import com.genvict.dssad.cloud.config.AppProperties;
import com.genvict.dssad.cloud.mqtt.model.UploadPriority;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 待确认报文登记表（QoS 1 应用的「应用层 ACK」）。
 *
 * <p>为什么 MQTT QoS 1 还需要应用层重发？因为 QoS 1 只能保证报文到达 Broker，
 * <b>不能保证对端业务处理成功</b>。文档 5.1.4.1 / 8.1 明确要求：下游收到
 * {@code res != 1} 时要间隔重发。本登记表负责：
 * <ol>
 *   <li>记录每条已发出的、需要 ACK 的报文（msgId → 报文快照）；</li>
 *   <li>收到回复时按 {@code ackMsgId} 完成配对并移除；</li>
 *   <li>回复状态为「消息有误」时把策略切换为 {@link RetryKind#INVALID}（3s / 最多 5 次）；</li>
 *   <li>为 {@code MqttBridgeMonitor} 提供「到期待重发」清单。</li>
 * </ol>
 *
 * <p>并发：{@link ConcurrentHashMap} + 不可变 {@link Pending} 记录，所有状态变更都是
 * {@code replace/put} 原子操作，无需额外锁；出站（发布线程）与入站（回调线程）可安全并行。
 */
@Slf4j
@Component
public class PendingAckRegistry {

    /** 重发策略类型。 */
    public enum RetryKind {
        /** 对端未回复（超时未确认）：文档 5.1.4.1 —— 间隔 5s，最多 3 次。 */
        CONFIRM,
        /** 对端明确回复异常（res=2/3）：文档 8.1 —— 间隔 3s，最多 5 次。 */
        INVALID
    }

    private final Map<String, Pending> registry = new ConcurrentHashMap<>();
    private final AppProperties.Retry retryConfig;

    public PendingAckRegistry(AppProperties properties) {
        this.retryConfig = properties.mqtt().retry();
    }

    /**
     * 登记一条待确认报文。
     *
     * @param msgId   报文的消息编号（对端将回填到 {@code ackMsgId}）
     * @param topic   目标 Topic
     * @param payload 报文对象（在此序列化一次，重发时直接复用，避免重复序列化开销）
     * @param qos     QoS
     * @param priority 离线补传优先级
     */
    public void register(String msgId, String topic, Object payload, int qos, UploadPriority priority) {
        if (msgId == null || msgId.isBlank()) {
            return;
        }
        long now = TimeUtils.nowMillis();
        registry.put(msgId, new Pending(msgId, topic, JsonUtils.toJson(payload), qos,
                priority, RetryKind.CONFIRM, 0, now + intervalMillis(RetryKind.CONFIRM), now));
    }

    /**
     * 收到成功确认（{@code res=1}），移除登记项。
     *
     * @return 被移除的登记项；若不存在（重复确认/已移除）返回 {@code null}
     */
    public Pending complete(String ackMsgId) {
        if (ackMsgId == null) {
            return null;
        }
        return registry.remove(ackMsgId);
    }

    /**
     * 收到异常确认（{@code res=2/3}），切换为 {@link RetryKind#INVALID} 策略并立即（下个扫描周期）重发。
     *
     * @return 切换后的登记项；不存在返回 {@code null}
     */
    public Pending markInvalid(String ackMsgId) {
        if (ackMsgId == null) {
            return null;
        }
        long now = TimeUtils.nowMillis();
        return registry.computeIfPresent(ackMsgId, (key, old) -> {
            log.warn("[待确认] 对端回复异常，切换重发策略 kind={} attempts={} topic={}",
                    RetryKind.INVALID, old.attempts() + 1, old.topic());
            return old.next(RetryKind.INVALID, now + intervalMillis(RetryKind.INVALID), old.attempts() + 1);
        });
    }

    /** 取出到期待重发的登记项（不再修改状态，由调用方调用 {@link #advance} 推进）。 */
    public List<Pending> due(long nowMillis) {
        List<Pending> result = new ArrayList<>();
        for (Pending pending : registry.values()) {
            if (pending.nextAttemptAt() <= nowMillis) {
                result.add(pending);
            }
        }
        return result;
    }

    /** 推进重发进度；超出最大次数则移除并返回 {@code false}。 */
    public boolean advance(Pending pending) {
        int maxAttempts = maxAttempts(pending.kind());
        if (pending.attempts() + 1 > maxAttempts) {
            registry.remove(pending.msgId());
            log.error("[待确认] 重发 {} 次仍未确认，放弃 msgId={} topic={}（请排查对端处理能力或报文合法性）",
                    pending.attempts(), pending.msgId(), pending.topic());
            return false;
        }
        long now = TimeUtils.nowMillis();
        registry.replace(pending.msgId(),
                pending.next(pending.kind(), now + intervalMillis(pending.kind()), pending.attempts() + 1));
        return true;
    }

    /** 主动移除。 */
    public Optional<Pending> remove(String msgId) {
        return msgId == null ? Optional.empty() : Optional.ofNullable(registry.remove(msgId));
    }

    /** 在册待确认条数（监控指标：持续增长说明对端异常）。 */
    public int size() {
        return registry.size();
    }

    /** 是否在册。 */
    public boolean contains(String msgId) {
        return msgId != null && registry.containsKey(msgId);
    }

    /** 清空（测试用）。 */
    public void clear() {
        registry.clear();
    }

    /** 指定策略下的重发间隔。 */
    public long intervalMillis(RetryKind kind) {
        return kind == RetryKind.INVALID ? retryConfig.invalidIntervalMillis() : retryConfig.confirmIntervalMillis();
    }

    /** 指定策略下的最大尝试次数。 */
    public int maxAttempts(RetryKind kind) {
        return kind == RetryKind.INVALID ? retryConfig.invalidMaxAttempts() : retryConfig.confirmMaxAttempts();
    }

    /**
     * 待确认报文快照（不可变）。
     *
     * @param msgId         报文编号
     * @param topic         目标 Topic
     * @param payloadJson   已序列化报文
     * @param qos           QoS
     * @param priority      补传优先级
     * @param kind          当前重发策略
     * @param attempts      已重发次数
     * @param nextAttemptAt 下次重发时间（毫秒）
     * @param createdAt     首次发送时间（毫秒）
     */
    public record Pending(String msgId, String topic, String payloadJson, int qos,
                          UploadPriority priority, RetryKind kind, int attempts,
                          long nextAttemptAt, long createdAt) {

        Pending next(RetryKind kind, long nextAttemptAt, int attempts) {
            return new Pending(msgId, topic, payloadJson, qos, priority, kind, attempts, nextAttemptAt, createdAt);
        }
    }
}
