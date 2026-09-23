package com.genvict.dssad.cloud.mqtt.model;

import lombok.Getter;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * MQTT 消息类型字典（对应附录 B.2 的 {@code mqttType} 命名规则）。
 *
 * <p>本枚举是<b>协议单一事实来源</b>：Topic 的构成、QoS、是否需要回复、回复主题、离线重传优先级
 * 全部从这张表推导，避免散落在各 Handler 中导致协议漂移。
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@code code}：{@code mqttType} 字面量；</li>
 *   <li>{@code naturalDirection}：协议规定的方向（UP=我方发布，DOWN=我方订阅）；</li>
 *   <li>{@code ackTypeCode}：需要回复时，回复报文的 {@code mqttType}；为 {@code null} 表示无需回复；</li>
 *   <li>{@code priority}：断线重连后的补传优先级（文档 8.4：事件 &gt; 基础 &gt; 状态）。</li>
 * </ul>
 *
 * <p><b>协议不一致提示</b>：{@code groute} 上行使用云云格式（enterpriseId），
 * 而 6.1.5.1.3 定义的 {@code groute-resp/down} 使用车云格式（vehicleType/vin）。
 * 本表按文档原文声明（回复走 {@link TopicScope#CAR}），订阅端做了双格式兼容，
 * 详见 {@code MqttTopics} 与详细设计说明书「文档缺陷与澄清项」。
 */
@Getter
public enum MqttType {

    // ===================== 车云交互（第 5 章） =====================

    /** 车辆准静态参数信息（连接/重连完成后上报）。 */
    INH("inh", "车辆准静态参数信息", TopicScope.CAR, TopicDirection.UP,
            "inh-resp", 1, UploadPriority.BASE, "5.1.4.2.1"),

    /** 车辆准静态参数回复。 */
    INH_RESP("inh-resp", "车辆准静态参数回复", TopicScope.CAR, TopicDirection.DOWN,
            null, 1, null, "5.1.5.1.1"),

    /** 车辆低频运行状态信息（60s 一次，QoS 0，无回复）。 */
    LOW_STATE("low-state", "车辆低频运行状态信息", TopicScope.CAR, TopicDirection.UP,
            null, 0, UploadPriority.STATE, "5.1.4.3.1"),

    /** 车辆低频基础信息（1s 一次，QoS 0，无回复）。 */
    LOW_BASE("low-base", "车辆低频基础信息", TopicScope.CAR, TopicDirection.UP,
            null, 0, UploadPriority.STATE, "5.1.4.3.2"),

    /** 车端导航路径信息。 */
    GROUTE("groute", "车端导航路径信息", TopicScope.ENTERPRISE, TopicDirection.UP,
            "groute-resp", 1, UploadPriority.BASE, "6.1.4.2.3"),

    /** 车端导航路径信息回复。 */
    GROUTE_RESP("groute-resp", "车端导航路径信息回复", TopicScope.CAR, TopicDirection.DOWN,
            null, 1, null, "6.1.5.1.3"),

    /** 车辆事故信息。 */
    ACCIDENT("accident", "车辆事故信息", TopicScope.CAR, TopicDirection.UP,
            "accident-resp", 1, UploadPriority.EVENT, "5.1.4.4.1"),

    /** 车辆事故信息回复。 */
    ACCIDENT_RESP("accident-resp", "车辆事故信息回复", TopicScope.CAR, TopicDirection.DOWN,
            null, 1, null, "5.1.5.1.2"),

    /** 车辆故障信息。 */
    ERROR_REPORT("error-report", "车辆故障信息", TopicScope.CAR, TopicDirection.UP,
            "error-report-resp", 1, UploadPriority.EVENT, "5.1.4.4.2"),

    /** 车辆故障信息回复。 */
    ERROR_REPORT_RESP("error-report-resp", "车辆故障信息回复", TopicScope.CAR, TopicDirection.DOWN,
            null, 1, null, "5.1.5.1.3"),

    /** 车端事故媒体数据请求（平台 → 车端，要求上传事故视频）。 */
    ACCIDENT_MEDIA("accident-media", "车端事故媒体数据请求", TopicScope.CAR, TopicDirection.DOWN,
            null, 1, null, "5.1.5.2.1"),

    /** 车端事故媒体数据请求回复（车端已受理，稍后通过 HTTP 上传视频）。 */
    ACCIDENT_MEDIA_RESP("accident-media-resp", "车端事故媒体数据请求回复", TopicScope.CAR, TopicDirection.UP,
            null, 1, UploadPriority.EVENT, "5.1.4.1.1"),

    // ===================== 云云交互（第 6 章） =====================

    /** 远程驾驶接管信息（企业云端 → 平台云端）。 */
    REMOTE_DRIVING("remote-driving", "远程驾驶接管信息", TopicScope.ENTERPRISE, TopicDirection.UP,
            "remote-driving-resp", 1, UploadPriority.EVENT, "6.1.4.2.1"),

    /** 远程驾驶接管信息回复。 */
    REMOTE_DRIVING_RESP("remote-driving-resp", "远程驾驶接管信息回复", TopicScope.ENTERPRISE, TopicDirection.DOWN,
            null, 1, null, "6.1.5.1.1"),

    /** 任务信息（企业云端 → 平台云端）。 */
    TASK("task", "任务信息", TopicScope.ENTERPRISE, TopicDirection.UP,
            "task-resp", 1, UploadPriority.EVENT, "6.1.4.2.2"),

    /** 任务信息回复。 */
    TASK_RESP("task-resp", "任务信息回复", TopicScope.ENTERPRISE, TopicDirection.DOWN,
            null, 1, null, "6.1.5.1.2"),

    /** 地图增强信息（平台云端 → 企业云端/车端，交通阻断设施）。 */
    MAPEHN("mapehn", "地图增强信息", TopicScope.ENTERPRISE, TopicDirection.DOWN,
            "mapehn-resp", 1, null, "6.1.5.2.1"),

    /** 地图增强信息回复。 */
    MAPEHN_RESP("mapehn-resp", "地图增强信息回复", TopicScope.ENTERPRISE, TopicDirection.UP,
            null, 1, UploadPriority.BASE, "6.1.4.1.1");

    private static final Map<String, MqttType> CODE_INDEX = Arrays.stream(values())
            .collect(Collectors.toMap(MqttType::getCode, Function.identity()));

    private final String code;
    private final String description;
    private final TopicScope scope;
    private final TopicDirection naturalDirection;
    private final String ackTypeCode;
    private final int qos;
    private final UploadPriority priority;
    private final String docRef;

    MqttType(String code, String description, TopicScope scope, TopicDirection naturalDirection,
             String ackTypeCode, int qos, UploadPriority priority, String docRef) {
        this.code = code;
        this.description = description;
        this.scope = scope;
        this.naturalDirection = naturalDirection;
        this.ackTypeCode = ackTypeCode;
        this.qos = qos;
        this.priority = priority;
        this.docRef = docRef;
    }

    /** 按 {@code mqttType} 字面量精确查找，未知返回 {@code null}。 */
    public static MqttType fromCode(String code) {
        if (code == null) {
            return null;
        }
        return CODE_INDEX.get(code.trim().toLowerCase());
    }

    /** 上行业务报文是否需要发送 ACK 回复。 */
    public boolean needAck() {
        return ackTypeCode != null && naturalDirection == TopicDirection.UP;
    }

    /** 需要回复时，回复报文类型；否则返回 {@code null}。 */
    public MqttType ackType() {
        return ackTypeCode == null ? null : fromCode(ackTypeCode);
    }

    /** 回复主题的作用域，由回复类型推导，避免双份协议定义。 */
    public TopicScope ackScope() {
        MqttType ackType = ackType();
        return ackType == null ? null : ackType.getScope();
    }

    /** 是否为「回复类」报文（本身即 ACK，不应再触发二次回复）。 */
    public boolean isAckMessage() {
        return code.endsWith("-resp");
    }
}
