package com.genvict.dssad.cloud.service;

import com.genvict.dssad.cloud.common.util.JsonUtils;
import com.genvict.dssad.cloud.common.util.TimeUtils;
import com.genvict.dssad.cloud.common.util.UuidUtils;
import com.genvict.dssad.cloud.config.AppProperties;
import com.genvict.dssad.cloud.mqtt.core.MqttPublisher;
import com.genvict.dssad.cloud.mqtt.core.OfflineMessageQueue;
import com.genvict.dssad.cloud.mqtt.core.PendingAckRegistry;
import com.genvict.dssad.cloud.mqtt.dto.AckMessage;
import com.genvict.dssad.cloud.mqtt.dto.DownMessages;
import com.genvict.dssad.cloud.mqtt.dto.EnterpriseUpMessages;
import com.genvict.dssad.cloud.mqtt.model.AckCode;
import com.genvict.dssad.cloud.mqtt.model.MqttType;
import com.genvict.dssad.cloud.mqtt.model.UploadPriority;
import com.genvict.dssad.cloud.mqtt.topic.TopicBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * MQTT 下行/上行指令服务：业务层发布报文的<b>唯一出口</b>。
 *
 * <p>统一处理四件事，避免业务代码各写一遍：
 * <ol>
 *   <li><b>Topic 构造</b>：一律走 {@link TopicBuilder}，杜绝手工拼串导致消息静默丢失；</li>
 *   <li><b>连接态判断 + 离线缓存</b>：未连接时自动进 {@link OfflineMessageQueue}，
 *       按文档 8.4 的优先级（事件 &gt; 基础 &gt; 状态）在重连后补传；</li>
 *   <li><b>待确认登记</b>：需要 ACK 的报文登记到 {@link PendingAckRegistry}，
 *       由 {@code MqttAckRetryScheduler} 按文档 5.1.4.1 / 8.1 的间隔重发；</li>
 *   <li><b>ID 生成</b>：统一生成 UUID 格式的 {@code msgId} 并在返回值中带出，
 *       调用方可据此关联后续应答。</li>
 * </ol>
 */
@Slf4j
@Service
public class MqttCommandService {

    private final MqttPublisher publisher;
    private final TopicBuilder topicBuilder;
    private final PendingAckRegistry pendingAckRegistry;
    private final OfflineMessageQueue offlineQueue;
    private final MqttAuditService auditService;
    private final AppProperties properties;

    public MqttCommandService(MqttPublisher publisher,
                              TopicBuilder topicBuilder,
                              PendingAckRegistry pendingAckRegistry,
                              OfflineMessageQueue offlineQueue,
                              MqttAuditService auditService,
                              AppProperties properties) {
        this.publisher = publisher;
        this.topicBuilder = topicBuilder;
        this.pendingAckRegistry = pendingAckRegistry;
        this.offlineQueue = offlineQueue;
        this.auditService = auditService;
        this.properties = properties;
    }

    // ==================== 车云下行：事故媒体请求（5.1.5.2.1） ====================

    /**
     * 请求车端上传事故媒体数据。
     *
     * <p>文档 3.3：事件触发时车端需保存「前 15 秒 + 后 5 秒」的四方向视频，
     * 平台收到结构化事故数据后，通过本方法下发请求，车端回 MQTT 应答后再走 HTTP 上传。
     *
     * @param vehicleType 车端类型（low_speed / high_speed），用于构造车云 Topic
     * @param vin         车辆 VIN
     * @param eventId     事故 ID
     * @param directions  需要的方向：1 前 / 2 右 / 3 后 / 4 左
     * @return 本次请求的 {@code msgId}（用于与 {@code accident-media-resp} 配对）
     */
    public String requestAccidentMedia(String vehicleType, String vin, String eventId, List<String> directions) {
        String msgId = UuidUtils.randomWithDash();
        DownMessages.AccidentMediaRequest payload = new DownMessages.AccidentMediaRequest(
                vin, msgId, TimeUtils.nowMillis(), eventId, directions, null);
        String topic = topicBuilder.downstream(MqttType.ACCIDENT_MEDIA,
                vehicleType == null ? TopicBuilder.VEHICLE_TYPE_LOW_SPEED : vehicleType, vin);
        publish(topic, payload, msgId, MqttType.ACCIDENT_MEDIA.getQos(),
                UploadPriority.EVENT, true);
        log.info("[指令] 下发事故媒体请求 vin={} eventId={} 方向={} msgId={}", vin, eventId, directions, msgId);
        return msgId;
    }

    // ==================== 云云上行：远驾 / 任务 / 导航（6.1.4.2） ====================

    /** 上报远程驾驶接管信息（发起/结束）。 */
    public String reportRemoteDriving(EnterpriseUpMessages.RemoteDriving payload) {
        if (payload.msgId() == null || payload.msgId().isBlank()) {
            throw new IllegalArgumentException("msgId 不能为空");
        }
        String topic = topicBuilder.natural(MqttType.REMOTE_DRIVING, null, null, properties.enterpriseId());
        publish(topic, payload, payload.msgId(), MqttType.REMOTE_DRIVING.getQos(),
                UploadPriority.EVENT, true);
        return payload.msgId();
    }

    /** 上报任务信息（开始/结束）。 */
    public String reportTask(EnterpriseUpMessages.Task payload) {
        if (payload.msgId() == null || payload.msgId().isBlank()) {
            throw new IllegalArgumentException("msgId 不能为空");
        }
        String topic = topicBuilder.natural(MqttType.TASK, null, null, properties.enterpriseId());
        publish(topic, payload, payload.msgId(), MqttType.TASK.getQos(), UploadPriority.EVENT, true);
        return payload.msgId();
    }

    /**
     * 上报车端导航路径信息。
     *
     * <p><b>协议不一致处理</b>：文档 6.1.4.2.3 的上行 Topic 使用云云格式（enterpriseId），
     * 而 6.1.5.1.3 的回复 Topic 使用车云格式（vehicleType/vin）。本实现按文档原文发布云云
     * 上行主题；对回复主题则两种格式<b>都订阅</b>（见 {@code TopicBuilder#subscriptionTopics}），
     * 保证无论监管平台按哪种格式回复都能正确配对。该差异已登记在详细设计说明书
     * 「文档缺陷与澄清项」，待平台方确认后收敛。
     */
    public String reportGroute(EnterpriseUpMessages.Groute payload) {
        if (payload.msgId() == null || payload.msgId().isBlank()) {
            throw new IllegalArgumentException("msgId 不能为空");
        }
        String topic = topicBuilder.natural(MqttType.GROUTE, null, null, properties.enterpriseId());
        publish(topic, payload, payload.msgId(), MqttType.GROUTE.getQos(), UploadPriority.BASE, true);
        return payload.msgId();
    }

    /** 回复地图增强信息（收到 {@code mapehn/down} 后必须回执）。 */
    public String replyMapehn(String msgId) {
        String responseMsgId = UuidUtils.randomWithDash();
        EnterpriseUpMessages.MapehnResp payload = new EnterpriseUpMessages.MapehnResp(
                properties.enterpriseId(), responseMsgId, msgId, TimeUtils.nowMillis(),
                AckCode.CONFIRM.getCode());
        String topic = topicBuilder.natural(MqttType.MAPEHN_RESP, null, null, properties.enterpriseId());
        publish(topic, payload, responseMsgId, MqttType.MAPEHN_RESP.getQos(), UploadPriority.BASE, false);
        return responseMsgId;
    }

    // ==================== 通用回复 ====================

    /**
     * 发送 ACK 回复。
     *
     * <p>ACK 报文本身<b>不再登记重发</b>（否则会形成 ACK 套 ACK 的无限递归）：
     * 若 ACK 丢失，由对端按其重发策略再次投递原报文，届时平台会再回一次 ACK，
     * 这是协议自带的收敛机制。
     *
     * @param requestTopic 请求报文的 Topic
     * @param request      请求元信息
     * @param requestMsgId 请求的 msgId
     * @param vin          车辆 VIN（云云场景可为 {@code null}）
     * @param code         回复状态
     */
    public void replyAck(com.genvict.dssad.cloud.mqtt.model.TopicInfo request,
                         String requestMsgId, String vin, AckCode code) {
        MqttType ackType = request.type() == null ? null : request.type().ackType();
        if (ackType == null) {
            return;
        }
        AckMessage ack = AckMessage.reply(request, requestMsgId, vin, code);
        // vehicleType 传 null：请求 Topic 中本来就有（车云场景），
        // 缺失时（云云 groute 的回复落到车云 Topic）由 TopicBuilder 取默认值并告警
        String topic = topicBuilder.ackTopic(request, ackType, vin, null);
        boolean ok = publisher.publish(topic, ack, ackType.getQos());
        auditService.recordOutbound(topic, ackType.getCode(),
                request.senderId(), requestMsgId, ack, ok ? "OK" : "FAILED");
        if (!ok) {
            log.warn("[回复] ACK 发送失败 topic={} ackMsgId={}", topic, requestMsgId);
        }
    }

    // ==================== 内部通用发布 ====================

    /**
     * 发布报文（含离线缓存与待确认登记）。
     *
     * @param topic      目标 Topic
     * @param payload    报文对象
     * @param msgId      消息编号
     * @param qos        QoS
     * @param priority   离线补传优先级
     * @param expectAck  是否需要等待对端 ACK（需要则登记重发）
     * @return 是否已成功发出（{@code false} 表示已进入离线队列）
     */
    public boolean publish(String topic, Object payload, String msgId, int qos,
                           UploadPriority priority, boolean expectAck) {
        if (publisher.isConnected()) {
            boolean ok = publisher.publish(topic, payload, qos);
            auditService.recordOutbound(topic, extractType(topic), null, msgId, payload,
                    ok ? "OK" : "FAILED");
            if (ok) {
                if (expectAck && msgId != null) {
                    pendingAckRegistry.register(msgId, topic, payload, qos,
                            priority == null ? UploadPriority.BASE : priority);
                }
                return true;
            }
        }
        // 未连接或发送失败：进入离线队列，重连后按优先级补传
        String json = payload instanceof String s ? s : JsonUtils.toJson(payload);
        boolean evicted = offlineQueue.offer(new OfflineMessageQueue.QueuedMessage(
                topic, json, qos, priority == null ? UploadPriority.BASE : priority, TimeUtils.nowMillis()));
        log.warn("[发布] MQTT 未连接，报文已进入离线队列 topic={} 队列长度={} 是否触发淘汰={}",
                topic, offlineQueue.size(), evicted);
        return false;
    }

    private String extractType(String topic) {
        int lastSlash = topic.lastIndexOf('/');
        int secondLast = topic.lastIndexOf('/', lastSlash - 1);
        return lastSlash > 0 && secondLast >= 0 ? topic.substring(secondLast + 1, lastSlash) : topic;
    }
}
