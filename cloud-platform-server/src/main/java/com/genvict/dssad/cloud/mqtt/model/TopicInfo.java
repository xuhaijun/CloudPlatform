package com.genvict.dssad.cloud.mqtt.model;

/**
 * 解析后的 Topic 元信息（不可变）。
 *
 * <p>支持三种合法形态（附录 B.1）：
 * <pre>
 * 车云上行  uvodp/{vehicleType}/{vin}/{mqttType}/up
 * 车云下行  uvodp/{vehicleType}/{vin}/{mqttType}/down          ← 正文实际使用（5 段）
 * 车云下行  uvodp/{vehicleType}/{vin}/{mqttType}/{version}/down ← 附录 B 定义（6 段，带版本）
 * 云云      uvodp/{enterpriseId}/{mqttType}/up|down
 * </pre>
 *
 * @param raw           原始 Topic 字符串（留痕用）
 * @param scope         作用域：车云 / 云云
 * @param vehicleType   无人车类型（云云时为 {@code null}）
 * @param vin           车辆 VIN（云云时为 {@code null}）
 * @param enterpriseId  企业 ID（车云时为 {@code null}）
 * @param mqttTypeCode  {@code mqttType} 字面量
 * @param type          经字典解析出的类型；未登记的 {@code mqttType} 为 {@code null}
 * @param direction     方向
 * @param version       协议版本段（仅车云 6 段下行存在，其余为 {@code null}）
 */
public record TopicInfo(
        String raw,
        TopicScope scope,
        String vehicleType,
        String vin,
        String enterpriseId,
        String mqttTypeCode,
        MqttType type,
        TopicDirection direction,
        String version) {

    /** 发送方标识：车云取 VIN，云云取企业 ID。用于日志、去重键、限流键。 */
    public String senderId() {
        return scope == TopicScope.CAR ? vin : enterpriseId;
    }

    /** 是否为我方需要处理的上行报文。 */
    public boolean isUpstream() {
        return direction == TopicDirection.UP;
    }

    /** {@code mqttType} 是否已在协议字典中登记。 */
    public boolean isKnownType() {
        return type != null;
    }
}
