package com.genvict.dssad.cloud.mqtt.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 协议字典测试：报文类型与接收标志。
 *
 * <p>这些断言的价值在于<b>把接口文档的枚举「钉死」在代码里</b>：
 * 任何人手滑改了 {@code code} 字面量、删了一个类型、或把 ACK 类型写反，
 * 测试立刻失败。协议字面量一旦上线就不可改（车端与监管平台按字面量订阅），
 * 因此这类测试的严格程度要高于普通业务测试。
 */
@DisplayName("协议字典：报文类型与接收标志")
class MqttTypeAndAckCodeTest {

    /** 文档定义的全部 18 个 mqttType 字面量（车云 12 + 云云 6）。 */
    private static final Set<String> EXPECTED_CODES = Set.of(
            // 车云（第 5 章）
            "inh", "inh-resp",
            "low-state", "low-base",
            "groute", "groute-resp",
            "accident", "accident-resp",
            "error-report", "error-report-resp",
            "accident-media", "accident-media-resp",
            // 云云（第 6 章）
            "remote-driving", "remote-driving-resp",
            "task", "task-resp",
            "mapehn", "mapehn-resp");

    @Test
    @DisplayName("报文类型集合与文档完全一致（多一个少一个都失败）")
    void codeSetMatchesDocument() {
        Set<String> actual = Arrays.stream(MqttType.values())
                .map(MqttType::getCode)
                .collect(Collectors.toSet());

        assertEquals(EXPECTED_CODES, actual,
                "报文类型字面量与接口文档不一致，检查是否有拼写错误或遗漏");
        assertEquals(EXPECTED_CODES.size(), MqttType.values().length, "存在重复的 mqttType 字面量");
    }

    @Test
    @DisplayName("每个类型都必须登记文档章节号，便于排障时对照原文")
    void everyTypeHasDocReference() {
        for (MqttType type : MqttType.values()) {
            assertNotNull(type.getDocRef(), type + " 缺少文档章节号");
            assertFalse(type.getDocRef().isBlank(), type + " 的文档章节号为空");
        }
    }

    @Test
    @DisplayName("上行且登记了 ackType 的报文才需要回复")
    void needAckRules() {
        // 上行 QoS 1 报文：需要 ACK
        assertTrue(MqttType.INH.needAck());
        assertTrue(MqttType.ACCIDENT.needAck());
        assertTrue(MqttType.ERROR_REPORT.needAck());
        assertTrue(MqttType.GROUTE.needAck());

        // 上行但 QoS 0、协议无回复：不需要 ACK
        assertFalse(MqttType.LOW_BASE.needAck(), "low-base 是 QoS 0，文档没有回复报文");
        assertFalse(MqttType.LOW_STATE.needAck(), "low-state 是 QoS 0，文档没有回复报文");

        // 事故媒体数据请求回复：它本身就是回复报文。附录 B.2 中
        // accident-media 的下游是「accident-media-resp 回复」，链条到此结束，
        // 车端随后走 HTTP 上传视频，不存在 accident-media-resp-resp。
        assertFalse(MqttType.ACCIDENT_MEDIA_RESP.needAck(),
                "它本身即回复报文，文档未定义针对它的回复，不应触发 ACK");

        // 下行报文：不需要 ACK（回复的是对端的责任）
        assertFalse(MqttType.INH_RESP.needAck());
        assertFalse(MqttType.ACCIDENT_MEDIA.needAck());
        assertFalse(MqttType.MAPEHN.needAck());
    }

    @Test
    @DisplayName("ackType 必须能解析回真实枚举，且回复方向恒为请求方向的反向")
    void ackTypeResolvesToRealEnum() {
        for (MqttType type : MqttType.values()) {
            if (type.getAckTypeCode() == null) {
                assertNull(type.ackType());
                continue;
            }
            MqttType ackType = type.ackType();
            assertNotNull(ackType, type + " 的 ackTypeCode=" + type.getAckTypeCode() + " 无法解析为枚举");
            assertTrue(ackType.isAckMessage(), ackType + " 的 code 必须以 -resp 结尾");
            // 不写死 DOWN：mapehn 的请求本身是下行（平台 → 企业云端），
            // 其回复 mapehn-resp 按文档 6.1.4.1.1 是上行。真正的不变量是「方向相反」。
            assertEquals(type.getNaturalDirection().opposite(), ackType.getNaturalDirection(),
                    "回复方向必须是请求方向的反向： " + type + " -> " + ackType);
        }
    }

    @Test
    @DisplayName("回复报文作用域应与请求一致 —— 唯一例外是 groute（文档缺陷，非实现缺陷）")
    void ackScopeMatchesRequestExceptDocumentedDefect() {
        for (MqttType type : MqttType.values()) {
            MqttType ackType = type.ackType();
            if (ackType == null) {
                continue;
            }
            if (type == MqttType.GROUTE) {
                // 文档 6.1.4.2.3：uvodp/{enterpriseId}/groute/up      （云云）
                // 文档 6.1.5.1.3：uvodp/{vehicleType}/{vin}/groute-resp/down（车云）
                // 两者作用域不同，故 TopicBuilder 支持回退 VIN，见其 ackTopic 注释。
                assertEquals(TopicScope.ENTERPRISE, type.getScope());
                assertEquals(TopicScope.CAR, ackType.getScope());
                continue;
            }
            assertEquals(type.getScope(), ackType.getScope(),
                    "回复报文作用域应与原报文一致： " + type + " -> " + ackType);
        }
    }

    @Test
    @DisplayName("回复类报文本身不应再触发二次回复（防止 ACK 风暴）")
    void ackMessageDoesNotTriggerAnotherAck() {
        for (MqttType type : MqttType.values()) {
            if (type.isAckMessage()) {
                assertNull(type.getAckTypeCode(),
                        type + " 是回复报文，不应再登记 ackType，否则会形成 ACK 互相触发的死循环");
            }
        }
    }

    @Test
    @DisplayName("QoS 取值只能是 0/1/2，且文档要求回复的报文必须是 QoS 1")
    void qosValuesAreLegal() {
        for (MqttType type : MqttType.values()) {
            assertTrue(type.getQos() >= 0 && type.getQos() <= 2,
                    type + " 的 QoS 非法：" + type.getQos());
            if (type.needAck()) {
                assertEquals(1, type.getQos(), type + " 需要 ACK，QoS 必须为 1（文档约定）");
            }
        }
    }

    @Test
    @DisplayName("上行业务报文必须登记离线补传优先级，下行报文不需要")
    void priorityRegisteredOnlyForUplink() {
        for (MqttType type : MqttType.values()) {
            if (type.getNaturalDirection() == TopicDirection.UP) {
                assertNotNull(type.getPriority(), type + " 是上行报文，必须登记补传优先级");
            } else {
                assertNull(type.getPriority(), type + " 是下行报文，不应有补传优先级");
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
            "inh, INH",
            "INH, INH",
            "  inh  , INH",
            "ACCIDENT-MEDIA-RESP, ACCIDENT_MEDIA_RESP",
            "mapehn-resp, MAPEHN_RESP"
    })
    @DisplayName("fromCode 大小写不敏感且去除首尾空格")
    void fromCodeIsLenient(String input, String expectedName) {
        assertEquals(MqttType.valueOf(expectedName.trim()), MqttType.fromCode(input));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"unknown-type", "inh-resp-2", "lowbase"})
    @DisplayName("未知类型返回 null（由路由回 res=3 不支持，而不是抛异常）")
    void fromCodeUnknownReturnsNull(String code) {
        assertNull(MqttType.fromCode(code));
    }

    @Test
    @DisplayName("接收标志：1 确认 / 2 消息有误 / 3 不支持")
    void ackCodeMapping() {
        assertEquals(AckCode.CONFIRM, AckCode.fromCode("1"));
        assertEquals(AckCode.INVALID, AckCode.fromCode("2"));
        assertEquals(AckCode.UNSUPPORTED, AckCode.fromCode("3"));

        assertTrue(AckCode.CONFIRM.isSuccess());
        assertFalse(AckCode.INVALID.isSuccess());
        assertFalse(AckCode.UNSUPPORTED.isSuccess());

        // 数值型 JSON 会被读成 "1"，字符串型读成 "1"，两者都要能识别
        assertEquals(AckCode.CONFIRM, AckCode.fromCode(" 1 "));
        assertNull(AckCode.fromCode("0"));
        assertNull(AckCode.fromCode(null));
        assertNull(AckCode.fromCode("yes"));
    }
}
