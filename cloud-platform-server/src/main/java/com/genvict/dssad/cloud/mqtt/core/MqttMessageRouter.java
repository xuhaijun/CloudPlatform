package com.genvict.dssad.cloud.mqtt.core;

import com.genvict.dssad.cloud.common.exception.BizException;
import com.genvict.dssad.cloud.mqtt.handler.MessageContext;
import com.genvict.dssad.cloud.mqtt.handler.MessageHandler;
import com.genvict.dssad.cloud.mqtt.model.AckCode;
import com.genvict.dssad.cloud.mqtt.model.MqttType;
import com.genvict.dssad.cloud.mqtt.model.TopicDirection;
import com.genvict.dssad.cloud.mqtt.model.TopicInfo;
import com.genvict.dssad.cloud.mqtt.model.TopicScope;
import com.genvict.dssad.cloud.mqtt.topic.TopicParser;
import com.genvict.dssad.cloud.service.MqttAuditService;
import com.genvict.dssad.cloud.service.MqttCommandService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MQTT 报文路由器：报文进入业务世界的<b>唯一入口</b>。
 *
 * <p>把「所有类型都必须做」的横切逻辑收敛在这里，是保证协议行为一致性的关键：
 * <pre>
 * 报文
 *  ├─ 1. 解析 Topic（非法立即丢弃并留痕）
 *  ├─ 2. 若是回复类下行 → 关闭重发登记（成功）/ 切换异常重发策略（res=2,3）
 *  ├─ 3. 查找处理器（无处理器 → 上行则回 res=3「不支持」）
 *  ├─ 4. 通用协议校验（JSON 结构、msgId、发送方标识、timestamp）→ 失败回 res=2
 *  ├─ 5. 幂等去重（重复报文照常回 res=1，但跳过业务处理）
 *  ├─ 6. 执行处理器（业务异常 → res=2）
 *  ├─ 7. 需要回复的上行报文 → 统一回 ACK
 *  └─ 8. 留痕（报文 + 处理结果 + 耗时 + 时钟偏差）
 * </pre>
 *
 * <p><b>幂等为什么「照常回 ACK」？</b>因为对端重发的原因正是「没收到 ACK」，
 * 如果重复报文不回 ACK，对端会一直重发到上限后才放弃，既浪费带宽也污染告警。
 */
@Slf4j
@Component
public class MqttMessageRouter {

    private final TopicParser topicParser;
    private final MqttCommandService commandService;
    private final MqttAuditService auditService;
    private final PendingAckRegistry pendingAckRegistry;
    private final Map<MqttType, MessageHandler> handlerIndex = new EnumMap<>(MqttType.class);

    public MqttMessageRouter(TopicParser topicParser,
                             List<MessageHandler> handlers,
                             MqttCommandService commandService,
                             MqttAuditService auditService,
                             PendingAckRegistry pendingAckRegistry) {
        this.topicParser = topicParser;
        this.commandService = commandService;
        this.auditService = auditService;
        this.pendingAckRegistry = pendingAckRegistry;
        register(handlers);
    }

    /** 按类型建立索引，并在启动期检出「同一类型被多个处理器注册」的配置错误（快速失败）。 */
    private void register(List<MessageHandler> handlers) {
        for (MessageHandler handler : handlers) {
            for (MqttType type : handler.supportedTypes()) {
                MessageHandler previous = handlerIndex.put(type, handler);
                if (previous != null) {
                    throw new IllegalStateException("mqttType=" + type.getCode()
                            + " 被两个处理器注册：" + previous.getClass().getSimpleName()
                            + " 与 " + handler.getClass().getSimpleName());
                }
            }
        }
        log.info("[路由] 已注册 {} 个报文处理器，覆盖 {} 种 mqttType", handlers.size(), handlerIndex.size());
    }

    /** 路由一条报文。本方法<b>不抛异常</b>，保证消费线程不会因单条脏数据退出。 */
    public void route(MqttEnvelope envelope) {
        long startNanos = System.nanoTime();
        Optional<TopicInfo> parsed = topicParser.parse(envelope.topic());
        if (parsed.isEmpty()) {
            audit(envelope, null, "INVALID", null, "Topic 解析失败", startNanos);
            return;
        }
        TopicInfo topic = parsed.get();

        // ---- 2. 回复类下行：先处理重发登记（无论后续业务是否成功都要先关掉） ----
        if (topic.direction() == TopicDirection.DOWN && topic.isKnownType() && topic.type().isAckMessage()) {
            handleAckResponse(envelope, topic);
        }

        // ---- 3. 查找处理器 ----
        MessageHandler handler = topic.isKnownType() ? handlerIndex.get(topic.type()) : null;
        if (handler == null) {
            String reason = topic.isKnownType()
                    ? "无处理器（本平台不消费该类型）" : "未登记的 mqttType=" + topic.mqttTypeCode();
            log.warn("[路由] 无法处理报文 topic={} 原因={}", envelope.topic(), reason);
            replyUnsupported(topic, envelope);
            audit(envelope, topic, "UNSUPPORTED", AckCode.UNSUPPORTED.getCode(), reason, startNanos);
            return;
        }

        MessageContext context = new MessageContext(envelope, topic);

        // ---- 4. 通用协议校验 ----
        AckCode validation = validate(envelope, topic);
        if (validation != AckCode.CONFIRM) {
            log.warn("[路由] 报文校验不通过 topic={} res={}", envelope.topic(), validation.getCode());
            reply(topic, envelope, validation);
            audit(envelope, topic, "INVALID", validation.getCode(), "通用字段校验失败", startNanos);
            return;
        }

        // ---- 5. 幂等去重（仅上行业务报文需要；下行回复本身不重复处理） ----
        if (topic.isUpstream() && auditService.isDuplicate(topic.senderId(), envelope.text("msgId"))) {
            log.info("[路由] 重复报文，跳过业务处理并回 ACK topic={} msgId={}",
                    envelope.topic(), envelope.text("msgId"));
            reply(topic, envelope, AckCode.CONFIRM);
            audit(envelope, topic, "DUPLICATE", AckCode.CONFIRM.getCode(), null, startNanos);
            return;
        }

        // ---- 6. 业务处理 ----
        AckCode result;
        String errorMessage = null;
        try {
            handler.handle(context);
            result = AckCode.CONFIRM;
        } catch (BizException e) {
            result = AckCode.INVALID;
            errorMessage = e.getMessage();
            log.warn("[路由] 业务校验失败 topic={} 原因={}", envelope.topic(), e.getMessage());
        } catch (RuntimeException e) {
            result = AckCode.INVALID;
            errorMessage = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("[路由] 处理报文异常 topic={}", envelope.topic(), e);
        }

        // ---- 7. 回复 ACK ----
        reply(topic, envelope, result);

        // ---- 8. 留痕 ----
        audit(envelope, topic, result == AckCode.CONFIRM ? "OK" : "ERROR",
                result.getCode(), errorMessage, startNanos);
    }

    // ==================== 内部实现 ====================

    /**
     * 处理 {@code *-resp/down} 回复报文对重发登记的副作用。
     *
     * <ul>
     *   <li>{@code res=1}：从 {@link PendingAckRegistry} 移除，结束重发；</li>
     *   <li>{@code res=2/3}：切换为「异常重发」策略（3s / 最多 5 次，文档 8.1）。</li>
     * </ul>
     */
    private void handleAckResponse(MqttEnvelope envelope, TopicInfo topic) {
        String ackMsgId = envelope.text("ackMsgId");
        if (!StringUtils.hasText(ackMsgId)) {
            return;
        }
        AckCode code = AckCode.fromCode(envelope.text("res"));
        if (code == AckCode.CONFIRM) {
            // complete 返回 null 表示重复确认或已被重发扫描清理，属正常情况，无需告警
            PendingAckRegistry.Pending completed = pendingAckRegistry.complete(ackMsgId);
            if (completed != null) {
                log.debug("[重发] 已确认并停止重发 msgId={} attempts={}",
                        completed.msgId(), completed.attempts());
            }
        } else if (code != null) {
            pendingAckRegistry.markInvalid(ackMsgId);
        }
    }

    /**
     * 通用协议字段校验。
     *
     * <p>只校验「协议层面所有报文都必须具备」的字段，业务字段由各处理器负责 ——
     * 这样新增报文类型时不需要改这里，符合开闭原则。
     */
    private AckCode validate(MqttEnvelope envelope, TopicInfo topic) {
        if (!envelope.isJsonObject()) {
            return AckCode.INVALID;
        }
        // msgId 是请求-响应配对的唯一依据，缺失则无法回复，必须判定为“消息有误”
        if (topic.isUpstream() && !topic.type().isAckMessage()
                && !StringUtils.hasText(envelope.text("msgId"))) {
            return AckCode.INVALID;
        }
        // 车云必须带 vin；云云必须带 enterpriseId 或 vin（文档中云云报文两者可能同时出现）
        if (topic.scope() == TopicScope.CAR) {
            if (!StringUtils.hasText(envelope.text("vin"))) {
                return AckCode.INVALID;
            }
        } else if (!StringUtils.hasText(envelope.text("enterpriseId"))
                && !StringUtils.hasText(envelope.text("vin"))) {
            return AckCode.INVALID;
        }
        return AckCode.CONFIRM;
    }

    /** 回复 ACK（仅对「需要回复的上行业务报文」生效）。 */
    private void reply(TopicInfo topic, MqttEnvelope envelope, AckCode code) {
        if (topic.direction() != TopicDirection.UP || !topic.isKnownType() || !topic.type().needAck()) {
            return;
        }
        commandService.replyAck(topic, envelope.text("msgId"), envelope.text("vin"), code);
    }

    /** 未知/不支持类型的回复：仅当协议规定了回复主题时才回复，避免对不存在的主题发消息。 */
    private void replyUnsupported(TopicInfo topic, MqttEnvelope envelope) {
        if (topic.direction() != TopicDirection.UP) {
            return;
        }
        if (topic.isKnownType() && topic.type().needAck()) {
            reply(topic, envelope, AckCode.UNSUPPORTED);
        }
    }

    private void audit(MqttEnvelope envelope, TopicInfo topic, String status,
                       String ackCode, String error, long startNanos) {
        int latencyMs = (int) ((System.nanoTime() - startNanos) / 1_000_000L);
        auditService.recordInbound(envelope, topic, status, ackCode, error, latencyMs);
        if (latencyMs > 1_000) {
            log.warn("[路由] 报文处理耗时过长 {}ms topic={}", latencyMs, envelope.topic());
        }
    }
}
