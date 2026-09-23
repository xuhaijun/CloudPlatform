package com.genvict.dssad.cloud.mqtt.topic;

import com.genvict.dssad.cloud.mqtt.model.MqttType;
import com.genvict.dssad.cloud.mqtt.model.TopicDirection;
import com.genvict.dssad.cloud.mqtt.model.TopicInfo;
import com.genvict.dssad.cloud.mqtt.model.TopicScope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * MQTT Topic 解析器（附录 B.1 命名规则的实现）。
 *
 * <p>解析策略：先按 {@code /} 分段，再按<b>段数</b>判定作用域，最后按<b>末段</b>判定方向。
 * 段数判定比正则更快（无回溯）且更易读：
 * <ul>
 *   <li>4 段 → 云云：{@code uvodp}/{enterpriseId}/{mqttType}/{direction}</li>
 *   <li>5 段 → 车云：{@code uvodp}/{vehicleType}/{vin}/{mqttType}/{direction}</li>
 *   <li>6 段 → 车云带版本：{@code uvodp}/{vehicleType}/{vin}/{mqttType}/{version}/{direction}</li>
 * </ul>
 *
 * <p>本类<b>不做业务校验</b>（如 vehicleType 枚举合法性）：协议演进时新增 vehicleType 不应导致
 * 报文被丢弃，非法值仅告警由上层决定，保证「解析层宽容、校验层严格」。
 */
@Slf4j
@Component
public class TopicParser {

    /** Topic 固定前缀（附录 B.1）。 */
    public static final String PREFIX = "uvodp";

    /** 车云 Topic 段数（无版本）。 */
    private static final int CAR_SEGMENTS = 5;

    /** 车云 Topic 段数（带版本）。 */
    private static final int CAR_SEGMENTS_WITH_VERSION = 6;

    /** 云云 Topic 段数。 */
    private static final int ENTERPRISE_SEGMENTS = 4;

    /**
     * 解析 Topic。
     *
     * @param raw 原始 Topic
     * @return 解析成功返回 {@link TopicInfo}，前缀/段数/方向不合法返回 {@link Optional#empty()}
     */
    public Optional<TopicInfo> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String topic = raw.trim();
        String[] segments = topic.split("/");
        if (segments.length < ENTERPRISE_SEGMENTS || !PREFIX.equals(segments[0])) {
            log.warn("[Topic] 非法前缀或段数不足，topic={}", topic);
            return Optional.empty();
        }

        TopicDirection direction = TopicDirection.fromSegment(segments[segments.length - 1]);
        if (direction == null) {
            log.warn("[Topic] 方向段非法（期望 up/down），topic={}", topic);
            return Optional.empty();
        }

        return switch (segments.length) {
            case ENTERPRISE_SEGMENTS -> Optional.of(buildEnterprise(segments, topic, direction));
            case CAR_SEGMENTS -> Optional.of(buildCar(segments, topic, direction, null));
            case CAR_SEGMENTS_WITH_VERSION ->
                    Optional.of(buildCar(segments, topic, direction, segments[4]));
            default -> {
                log.warn("[Topic] 段数不被支持，segments={} topic={}", segments.length, topic);
                yield Optional.empty();
            }
        };
    }

    private TopicInfo buildEnterprise(String[] segments, String topic, TopicDirection direction) {
        String enterpriseId = segments[1];
        String mqttTypeCode = segments[2];
        return new TopicInfo(topic, TopicScope.ENTERPRISE, null, null, enterpriseId,
                mqttTypeCode, MqttType.fromCode(mqttTypeCode), direction, null);
    }

    private TopicInfo buildCar(String[] segments, String topic, TopicDirection direction, String version) {
        String vehicleType = segments[1];
        String vin = segments[2];
        String mqttTypeCode = segments[3];
        return new TopicInfo(topic, TopicScope.CAR, vehicleType, vin, null,
                mqttTypeCode, MqttType.fromCode(mqttTypeCode), direction, version);
    }
}
