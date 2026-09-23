package com.genvict.dssad.cloud.mqtt.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.genvict.dssad.cloud.common.util.JsonUtils;
import com.genvict.dssad.cloud.common.util.TimeUtils;

import java.nio.charset.StandardCharsets;

/**
 * 收到的一条 MQTT 报文（不可变快照）。
 *
 * <p>解析在<b>入站边界一次性完成</b>：后续 Router / Handler 只消费 {@link #payload()}，
 * 不再重复反序列化，避免一条 1Hz 的报文在链路中被解析多次。
 *
 * @param topic      原始 Topic
 * @param rawPayload 原始报文字符串（用于留痕与问题回溯）
 * @param payload    解析后的 JSON 树；报文非法 JSON 时为 {@code null}
 * @param receivedAt 平台接收时间（毫秒），与报文内 {@code timestamp} 区分开，便于排查端到端时延
 * @param sizeBytes  报文字节数（构造时计算一次），用于指标统计与体积限制校验
 */
public record MqttEnvelope(String topic, String rawPayload, JsonNode payload, long receivedAt, int sizeBytes) {

    /**
     * 由原始报文字节构造。
     *
     * <p>JSON 解析失败不抛异常：MQTT 回调线程中抛异常会导致客户端把该条消息视为处理失败，
     * 影响后续报文的处理。此处降级为 {@code payload = null}，由 Router 统一回复 {@code res=2}。
     */
    public static MqttEnvelope of(String topic, byte[] rawPayload) {
        byte[] bytes = rawPayload == null ? new byte[0] : rawPayload;
        String text = new String(bytes, StandardCharsets.UTF_8);
        JsonNode node = null;
        try {
            if (!text.isBlank()) {
                node = JsonUtils.readTree(text);
            }
        } catch (RuntimeException ignored) {
            // 交由 Router 判定为「消息有误」，此处仅降级
        }
        return new MqttEnvelope(topic, text, node, TimeUtils.nowMillis(), bytes.length);
    }

    /** 报文是否为合法的 JSON 对象。 */
    public boolean isJsonObject() {
        return payload != null && payload.isObject();
    }

    /** 读取字符串字段，缺失返回 {@code null}。 */
    public String text(String field) {
        if (payload == null) {
            return null;
        }
        JsonNode node = payload.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    /** 读取长整型字段，缺失或非数字返回 {@code null}。 */
    public Long longValue(String field) {
        if (payload == null) {
            return null;
        }
        JsonNode node = payload.get(field);
        return node == null || !node.isNumber() ? null : node.asLong();
    }
}
