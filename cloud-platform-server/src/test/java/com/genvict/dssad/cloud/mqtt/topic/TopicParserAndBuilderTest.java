package com.genvict.dssad.cloud.mqtt.topic;

import com.genvict.dssad.cloud.mqtt.model.MqttType;
import com.genvict.dssad.cloud.mqtt.model.TopicDirection;
import com.genvict.dssad.cloud.mqtt.model.TopicInfo;
import com.genvict.dssad.cloud.mqtt.model.TopicScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Topic 解析与构造测试。
 *
 * <p>这组用例是整个平台<b>最重要的回归防线</b>：Topic 拼错的表现是
 * 「消息静默丢失」—— 对端订阅不到也不报错，排查一次要花数小时。
 * 因此这里既覆盖正文用到的 5 段下行，也覆盖附录 B 定义的 6 段（带版本）下行，
 * 并用「构造 → 解析」往返一致来锁死读写对称性。
 */
@DisplayName("Topic 解析与构造")
class TopicParserAndBuilderTest {

    private final TopicParser parser = new TopicParser();
    private final TopicBuilder builder = new TopicBuilder();

    // ==================== 解析 ====================

    @Nested
    @DisplayName("解析（Parser）")
    class Parse {

        @Test
        @DisplayName("车云上行：uvodp/{vehicleType}/{vin}/{mqttType}/up")
        void parseCarUplink() {
            TopicInfo info = parser.parse("uvodp/low_speed/LSV9123456789012/low-base/up").orElseThrow();

            assertEquals(TopicScope.CAR, info.scope());
            assertEquals("low_speed", info.vehicleType());
            assertEquals("LSV9123456789012", info.vin());
            assertNull(info.enterpriseId(), "车云报文不应解析出 enterpriseId");
            assertEquals(MqttType.LOW_BASE, info.type());
            assertEquals(TopicDirection.UP, info.direction());
            assertTrue(info.isUpstream());
            assertTrue(info.isKnownType());
            // 发送方标识用于去重键与限流键，车云取 VIN
            assertEquals("LSV9123456789012", info.senderId());
        }

        @Test
        @DisplayName("云云上行：uvodp/{enterpriseId}/{mqttType}/up，senderId 取企业 ID")
        void parseEnterpriseUplink() {
            TopicInfo info = parser.parse("uvodp/DSSAD-TEST-0001/mapehn-resp/up").orElseThrow();

            assertEquals(TopicScope.ENTERPRISE, info.scope());
            assertEquals("DSSAD-TEST-0001", info.enterpriseId());
            assertNull(info.vin());
            assertEquals(MqttType.MAPEHN_RESP, info.type());
            assertEquals("DSSAD-TEST-0001", info.senderId());
        }

        @Test
        @DisplayName("车云下行 5 段（正文实际写法）：uvodp/{vehicleType}/{vin}/{mqttType}/down")
        void parseCarDownlinkFiveSegments() {
            TopicInfo info = parser.parse("uvodp/low_speed/LSV9123456789012/accident-media/down")
                    .orElseThrow();

            assertEquals(MqttType.ACCIDENT_MEDIA, info.type());
            assertEquals(TopicDirection.DOWN, info.direction());
            assertFalse(info.isUpstream());
            assertNull(info.version(), "5 段格式没有版本段");
        }

        @Test
        @DisplayName("车云下行 6 段（附录 B 定义）：带 version 段")
        void parseCarDownlinkSixSegments() {
            TopicInfo info = parser.parse("uvodp/low_speed/LSV9123456789012/inh-resp/1.0/down")
                    .orElseThrow();

            assertEquals(MqttType.INH_RESP, info.type());
            assertEquals(TopicDirection.DOWN, info.direction());
            assertEquals("1.0", info.version());
        }

        @Test
        @DisplayName("未登记的 mqttType 仍可解析，但 type 为 null（由路由判定不支持）")
        void parseUnknownTypeKeepsMetadata() {
            TopicInfo info = parser.parse("uvodp/low_speed/LSV9123456789012/brand-new-type/up")
                    .orElseThrow();

            assertNull(info.type());
            assertFalse(info.isKnownType());
            assertEquals("brand-new-type", info.mqttTypeCode());
            // 关键：即使类型未知，也要能拿到 VIN，才能把「不支持」的 ACK 正确回给车端
            assertEquals("LSV9123456789012", info.vin());
        }

        @Test
        @DisplayName("前缀或段数不合法时返回 empty，而不是抛异常")
        void parseInvalidReturnsEmpty() {
            assertTrue(parser.parse("other/low_speed/VIN/low-base/up").isEmpty());
            assertTrue(parser.parse("uvodp").isEmpty());
            assertTrue(parser.parse("uvodp/low_speed").isEmpty());
            assertTrue(parser.parse("uvodp/low_speed/VIN/low-base").isEmpty());
            assertTrue(parser.parse("uvodp/low_speed/VIN/low-base/up/extra/seg").isEmpty());
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "/", "///", "uvodp//"})
        @DisplayName("空值与畸形入参不抛异常")
        void parseBlankIsSafe(String raw) {
            assertEquals(Optional.empty(), parser.parse(raw));
        }

        @Test
        @DisplayName("方向段非法时视为无法解析")
        void parseInvalidDirection() {
            assertTrue(parser.parse("uvodp/low_speed/VIN/low-base/sideways").isEmpty());
        }
    }

    // ==================== 构造 ====================

    @Nested
    @DisplayName("构造（Builder）")
    class Build {

        @Test
        @DisplayName("车云 Topic 必须给出 vehicleType 与 vin，否则立刻失败")
        void carScopeRequiresIdentity() {
            assertThrows(IllegalArgumentException.class,
                    () -> builder.build(MqttType.LOW_BASE, TopicDirection.UP, null, "VIN", null, null));
            assertThrows(IllegalArgumentException.class,
                    () -> builder.build(MqttType.LOW_BASE, TopicDirection.UP, "low_speed", "", null, null));
        }

        @Test
        @DisplayName("云云 Topic 必须给出 enterpriseId")
        void enterpriseScopeRequiresEnterpriseId() {
            assertThrows(IllegalArgumentException.class,
                    () -> builder.build(MqttType.TASK, TopicDirection.UP, null, null, null, null));
        }

        @Test
        @DisplayName("类型为 null 时立即失败（防止拼出 uvodp/.../null/up 这类脏 Topic）")
        void nullTypeFails() {
            assertThrows(IllegalArgumentException.class,
                    () -> builder.build(null, TopicDirection.UP, "low_speed", "VIN", null, null));
        }

        @Test
        @DisplayName("ACK Topic 镜像请求身份、类型取回复类型（inh/up → inh-resp/down）")
        void ackTopicMirrorsIdentity() {
            TopicInfo request = parser.parse("uvodp/low_speed/LSV9123456789012/inh/up").orElseThrow();
            String ack = builder.ackTopic(request, MqttType.INH_RESP, null, null);

            assertEquals("uvodp/low_speed/LSV9123456789012/inh-resp/down", ack);

            // 回复 Topic 必须能被解析回来，且方向为下行
            TopicInfo parsed = parser.parse(ack).orElseThrow();
            assertEquals(MqttType.INH_RESP, parsed.type());
            assertEquals(TopicDirection.DOWN, parsed.direction());
            assertEquals(request.vin(), parsed.vin());
        }

        @Test
        @DisplayName("云云 ACK 走企业作用域（mapehn 请求 → mapehn-resp/up）")
        void ackTopicEnterpriseScope() {
            TopicInfo request = parser.parse("uvodp/DSSAD-TEST-0001/mapehn/down").orElseThrow();
            String ack = builder.ackTopic(request, MqttType.MAPEHN_RESP, null, null);

            assertEquals("uvodp/DSSAD-TEST-0001/mapehn-resp/up", ack);
        }

        @Test
        @DisplayName("文档缺陷兜底：云云 groute/up 的回复落到车云 Topic，用报文体 VIN 回退定位")
        void ackTopicFallsBackToPayloadVinForGroute() {
            TopicInfo request = parser.parse("uvodp/DSSAD-TEST-0001/groute/up").orElseThrow();
            // 请求 Topic 中没有 vin / vehicleType，若不回退会抛 IllegalArgumentException，
            // 导致车端永远收不到 ACK
            String ack = builder.ackTopic(request, MqttType.GROUTE_RESP, "LSV9123456789012", "high_speed");

            assertEquals("uvodp/high_speed/LSV9123456789012/groute-resp/down", ack);

            TopicInfo parsed = parser.parse(ack).orElseThrow();
            assertEquals(MqttType.GROUTE_RESP, parsed.type());
            assertEquals("LSV9123456789012", parsed.vin());
            assertEquals("high_speed", parsed.vehicleType());
        }

        @Test
        @DisplayName("回退 VIN 与车端类型都缺失时使用默认车端类型，而不是抛异常")
        void ackTopicUsesDefaultVehicleTypeWhenUnknown() {
            TopicInfo request = parser.parse("uvodp/DSSAD-TEST-0001/groute/up").orElseThrow();
            String ack = builder.ackTopic(request, MqttType.GROUTE_RESP, "LSV9123456789012", null);

            assertEquals("uvodp/low_speed/LSV9123456789012/groute-resp/down", ack);
        }

        @Test
        @DisplayName("订阅过滤器：开启车云上行时 3 个，关闭时 2 个")
        void subscriptionTopics() {
            List<String> withCarUplink = builder.subscriptionTopics("ENT-1", true);
            assertEquals(3, withCarUplink.size());
            assertTrue(withCarUplink.contains("uvodp/ENT-1/+/down"));
            assertTrue(withCarUplink.contains("uvodp/+/+/+/down"));
            assertTrue(withCarUplink.contains("uvodp/+/+/+/up"));

            List<String> withoutCarUplink = builder.subscriptionTopics("ENT-1", false);
            assertEquals(2, withoutCarUplink.size());
            assertFalse(withoutCarUplink.contains("uvodp/+/+/+/up"));
        }
    }

    // ==================== 往返一致性 ====================

    @Test
    @DisplayName("往返一致：每种报文类型「构造 → 解析」后字段完全还原")
    void roundTripForEveryMessageType() {
        for (MqttType type : MqttType.values()) {
            String topic = builder.natural(type, "low_speed", "LSV9123456789012", "ENT-1");
            TopicInfo info = parser.parse(topic)
                    .orElseThrow(() -> new AssertionError("构造出的 Topic 无法解析：" + topic));

            assertEquals(type, info.type(), "类型还原失败：" + topic);
            assertEquals(type.getScope(), info.scope(), "作用域还原失败：" + topic);
            assertEquals(type.getNaturalDirection(), info.direction(), "方向还原失败：" + topic);
            assertEquals(type.getCode(), info.mqttTypeCode(), "类型字面量还原失败：" + topic);
        }
    }

    @Test
    @DisplayName("下行 Topic 往返一致（5 段格式）")
    void roundTripDownstream() {
        for (MqttType type : MqttType.values()) {
            if (type.getScope() != TopicScope.CAR) {
                continue;
            }
            String topic = builder.downstream(type, "high_speed", "LSV9123456789012");
            TopicInfo info = parser.parse(topic).orElseThrow();

            assertEquals(type, info.type());
            assertEquals(TopicDirection.DOWN, info.direction());
            assertEquals("high_speed", info.vehicleType());
        }
    }
}
