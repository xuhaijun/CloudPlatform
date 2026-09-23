package com.genvict.dssad.cloud.service;

import com.genvict.dssad.cloud.common.model.PageResult;
import com.genvict.dssad.cloud.common.store.StateStore;
import com.genvict.dssad.cloud.common.util.SnowflakeIdGenerator;
import com.genvict.dssad.cloud.common.util.TimeUtils;
import com.genvict.dssad.cloud.config.AppProperties;
import com.genvict.dssad.cloud.domain.entity.MqttMessageLog;
import com.genvict.dssad.cloud.domain.repository.MqttMessageLogRepository;
import com.genvict.dssad.cloud.mqtt.core.MqttEnvelope;
import com.genvict.dssad.cloud.mqtt.model.TopicInfo;
import com.genvict.dssad.cloud.mqtt.model.UploadPriority;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * MQTT 报文审计与幂等去重服务。
 *
 * <p>两项职责都属于「链路的横切关注点」，因此集中在入口处完成，避免每个 Handler 各写一遍。
 *
 * <h3>1. 幂等去重</h3>
 * QoS 1 只保证「至少一次」，车端在未收到 ACK 时必然重发。去重键为
 * {@code (senderId, msgId)}：<b>不同车辆的 msgId 可能相同</b>（若车端用简单计数或
 * 时间戳生成），因此必须带上发送方，否则会误判为重复而丢数据。
 * 去重窗口 10 分钟，远大于文档 8.1 的最大重发周期（3s × 5 次）。
 *
 * <h3>2. 留痕</h3>
 * 全量记录报文与处理结果，满足监管取证要求；同时记录
 * {@code clockSkewMs}（报文时间与平台接收时间之差），用于发现车端时钟未同步的问题
 * —— 时钟漂移会直接破坏轨迹回放的准确性，是实际联调中最常见的隐性缺陷。
 *
 * <p>性能：留痕表是高频写入表，同样采用「应用侧分配主键 + 单条 INSERT」，
 * 并支持按 {@code dssad.audit.mode} 采样，避免留痕本身成为瓶颈。
 */
@Slf4j
@Service
public class MqttAuditService {

    /** 去重窗口。 */
    private static final Duration DEDUP_TTL = Duration.ofMinutes(10);

    /** 报文体内存储时截断长度，防止超大报文（≤256KB）撑爆单行。 */
    private static final int PAYLOAD_STORE_LIMIT = 8_000;

    private final MqttMessageLogRepository messageLogRepository;
    private final StateStore stateStore;
    private final SnowflakeIdGenerator idGenerator;
    private final AppProperties.Audit auditConfig;

    public MqttAuditService(MqttMessageLogRepository messageLogRepository,
                            StateStore stateStore,
                            SnowflakeIdGenerator idGenerator,
                            AppProperties properties) {
        this.messageLogRepository = messageLogRepository;
        this.stateStore = stateStore;
        this.idGenerator = idGenerator;
        this.auditConfig = properties.audit();
    }

    /**
     * 判断报文是否为重复投递。
     *
     * @param senderId 发送方（VIN 或 enterpriseId）
     * @param msgId    报文编号
     * @return 已处理过返回 {@code true}（调用方应<b>照常回复 ACK 但跳过业务处理</b>）
     */
    public boolean isDuplicate(String senderId, String msgId) {
        if (!StringUtils.hasText(msgId)) {
            // 无 msgId 无法去重（协议要求必填，但容错处理：放行）
            return false;
        }
        String key = "dedup:" + senderId + ':' + msgId;
        boolean first = stateStore.setIfAbsent(key, "1", DEDUP_TTL);
        if (!first) {
            log.debug("[去重] 检测到重复报文 sender={} msgId={}", senderId, msgId);
        }
        return !first;
    }

    /**
     * 记录一条入站报文。
     *
     * @param envelope  报文
     * @param topicInfo 解析后的 Topic 信息（未解析成功时可为 {@code null}）
     * @param status    处理结果：OK / INVALID / UNSUPPORTED / ERROR / DROPPED
     * @param ackCode   回复的 {@code res} 值
     * @param error     失败原因摘要
     * @param latencyMs 处理耗时
     */
    @Transactional
    public void recordInbound(MqttEnvelope envelope, TopicInfo topicInfo,
                              String status, String ackCode, String error, int latencyMs) {
        if (!shouldRecord(topicInfo)) {
            return;
        }
        try {
            MqttMessageLog entity = new MqttMessageLog();
            entity.setId(idGenerator.nextId());
            entity.setTopic(envelope.topic());
            entity.setDirection(topicInfo == null ? null : topicInfo.direction().name());
            entity.setMqttType(topicInfo == null ? null : topicInfo.mqttTypeCode());
            entity.setSenderId(topicInfo == null ? null : topicInfo.senderId());
            entity.setMsgId(envelope.text("msgId"));
            entity.setPayload(truncate(envelope.rawPayload()));
            entity.setSizeBytes(envelope.sizeBytes());
            entity.setReceivedAt(TimeUtils.toInstant(envelope.receivedAt()));
            entity.setProcessStatus(status);
            entity.setAckCode(ackCode);
            entity.setErrorMessage(truncate(error));
            entity.setLatencyMs(latencyMs);
            Long payloadTs = envelope.longValue("timestamp");
            entity.setClockSkewMs(payloadTs == null ? null : payloadTs - envelope.receivedAt());
            messageLogRepository.save(entity);
        } catch (RuntimeException e) {
            // 留痕失败绝不能影响主业务链路
            log.error("[留痕] 报文留痕失败 topic={}", envelope.topic(), e);
        }
    }

    /** 记录一条出站报文（下行指令）。 */
    @Transactional
    public void recordOutbound(String topic, String mqttType, String senderId,
                               String msgId, Object payload, String status) {
        try {
            MqttMessageLog entity = new MqttMessageLog();
            entity.setId(idGenerator.nextId());
            entity.setTopic(topic);
            entity.setDirection("UP");
            entity.setMqttType(mqttType);
            entity.setSenderId(senderId);
            entity.setMsgId(msgId);
            String json = payload == null ? null
                    : (payload instanceof String s ? s : com.genvict.dssad.cloud.common.util.JsonUtils.toJson(payload));
            entity.setPayload(truncate(json));
            entity.setSizeBytes(json == null ? 0 : json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            entity.setReceivedAt(Instant.now());
            entity.setProcessStatus(status);
            messageLogRepository.save(entity);
        } catch (RuntimeException e) {
            log.error("[留痕] 出站报文留痕失败 topic={}", topic, e);
        }
    }

    /** 报文留痕分页查询。 */
    @Transactional(readOnly = true)
    public PageResult<MqttMessageLog> page(String senderId, String mqttType, String processStatus,
                                          int page, int size, int maxPageSize) {
        int safeSize = Math.min(Math.max(size, 1), maxPageSize);
        int safePage = Math.max(page, 1);
        PageRequest pageable = PageRequest.of(safePage - 1, safeSize,
                Sort.by(Sort.Direction.DESC, "receivedAt"));
        Page<MqttMessageLog> result;
        if (StringUtils.hasText(senderId)) {
            result = messageLogRepository.findBySenderIdOrderByReceivedAtDesc(senderId, pageable);
        } else if (StringUtils.hasText(mqttType)) {
            result = messageLogRepository.findByMqttTypeOrderByReceivedAtDesc(mqttType, pageable);
        } else if (StringUtils.hasText(processStatus)) {
            result = messageLogRepository.findByProcessStatusOrderByReceivedAtDesc(processStatus, pageable);
        } else {
            result = messageLogRepository.findAll(pageable);
        }
        return PageResult.of(result.getContent(), result.getTotalElements(), safePage, safeSize);
    }

    /** 按 msgId 追溯全链路报文。 */
    @Transactional(readOnly = true)
    public List<MqttMessageLog> traceByMsgId(String msgId) {
        return messageLogRepository.findByMsgId(msgId);
    }

    /** 时间窗口内的报文统计（监控页用）。 */
    @Transactional(readOnly = true)
    public AuditStats stats(int windowMinutes) {
        Instant since = Instant.now().minus(Duration.ofMinutes(windowMinutes));
        return new AuditStats(
                messageLogRepository.countByReceivedAtAfter(since),
                messageLogRepository.countByProcessStatusAndReceivedAtAfter("INVALID", since),
                messageLogRepository.countByProcessStatusAndReceivedAtAfter("UNSUPPORTED", since),
                messageLogRepository.countByProcessStatusAndReceivedAtAfter("ERROR", since),
                messageLogRepository.averageLatencySince(since));
    }

    /**
     * 清理超期留痕（每天凌晨 3 点执行，避免与业务高峰争抢 IO）。
     *
     * <p>分批删除而不是一条大 DELETE：千万级删除会长时间持锁并生成巨大 undo，
     * 分批（每批 5000）可让主从复制与业务查询平滑。
     */
    @Scheduled(cron = "0 0 3 * * ?")
    @Transactional
    public void purgeExpired() {
        Instant before = Instant.now().minus(Duration.ofDays(auditConfig.retentionDays()));
        int total = 0;
        int batch;
        do {
            batch = messageLogRepository.deleteBefore(before);
            total += batch;
        } while (batch > 0);
        if (total > 0) {
            log.info("[留痕] 已清理 {} 条超期报文（保留 {} 天）", total, auditConfig.retentionDays());
        }
    }

    /** 是否应记录本条报文（按留痕模式判定）。 */
    private boolean shouldRecord(TopicInfo topicInfo) {
        String mode = auditConfig.mode() == null ? "full" : auditConfig.mode().toLowerCase();
        return switch (mode) {
            case "event-only" -> topicInfo != null && topicInfo.type() != null
                    && topicInfo.type().getPriority() == UploadPriority.EVENT;
            case "sampled" -> ThreadLocalRandom.current().nextDouble() < auditConfig.sampleRate();
            default -> true;
        };
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= PAYLOAD_STORE_LIMIT ? text : text.substring(0, PAYLOAD_STORE_LIMIT) + "...[截断]";
    }

    /**
     * 报文统计。
     *
     * @param total       总报文数
     * @param invalid     报文有误数（res=2）
     * @param unsupported 不支持类型数（res=3）
     * @param error       处理异常数
     * @param avgLatencyMs 平均处理耗时
     */
    public record AuditStats(long total, long invalid, long unsupported, long error, Double avgLatencyMs) {
    }
}
