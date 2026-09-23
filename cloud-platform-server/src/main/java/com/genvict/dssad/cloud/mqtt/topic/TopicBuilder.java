package com.genvict.dssad.cloud.mqtt.topic;

import com.genvict.dssad.cloud.mqtt.model.MqttType;
import com.genvict.dssad.cloud.mqtt.model.TopicDirection;
import com.genvict.dssad.cloud.mqtt.model.TopicInfo;
import com.genvict.dssad.cloud.mqtt.model.TopicScope;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * MQTT Topic 构造器，与 {@link TopicParser} 严格互逆。
 *
 * <p>所有向外发送的 Topic 必须经本类构造，禁止在业务代码中拼接字符串 —— 这是防止
 * 「Topic 拼错导致消息静默丢失（对端订阅不到、也不报错）」的唯一有效手段。
 *
 * <p>注意：Topic 中的 {@code {version}} 段仅在车云端<b>下行</b>出现（附录 B.1），
 * 上行不带版本；当 {@code version} 为空时自动省略该段，兼容正文中 5 段写法的下行 Topic。
 */
@Component
public class TopicBuilder {

    /** 车端类型：功能性无人车。 */
    public static final String VEHICLE_TYPE_LOW_SPEED = "low_speed";

    /** 车端类型：智能网联车。 */
    public static final String VEHICLE_TYPE_HIGH_SPEED = "high_speed";

    /**
     * 构造 Topic（作用域由 {@link MqttType#getScope()} 决定）。
     *
     * @param type         消息类型
     * @param direction    方向
     * @param vehicleType  车端类型（车云必填），如 {@code low_speed}
     * @param vin          车辆 VIN（车云必填）
     * @param enterpriseId 企业 ID（云云必填）
     * @param version      协议版本段（仅车云下行可选）
     * @return Topic 字符串
     */
    public String build(MqttType type, TopicDirection direction,
                        String vehicleType, String vin, String enterpriseId, String version) {
        if (type == null) {
            throw new IllegalArgumentException("mqttType 不能为空");
        }
        StringBuilder sb = new StringBuilder(TopicParser.PREFIX);
        if (type.getScope() == TopicScope.CAR) {
            requireText(vehicleType, "vehicleType");
            requireText(vin, "vin");
            sb.append('/').append(vehicleType).append('/').append(vin);
        } else {
            requireText(enterpriseId, "enterpriseId");
            sb.append('/').append(enterpriseId);
        }
        sb.append('/').append(type.getCode());
        if (StringUtils.hasText(version)) {
            sb.append('/').append(version);
        }
        sb.append('/').append(direction.segment());
        return sb.toString();
    }

    /** 按类型协议方向构造（我方发布 up / 订阅 down 时的标准写法）。 */
    public String natural(MqttType type, String vehicleType, String vin, String enterpriseId) {
        return build(type, type.getNaturalDirection(), vehicleType, vin, enterpriseId, null);
    }

    /**
     * 构造 ACK 回复 Topic：<b>身份信息镜像请求报文</b>，类型与作用域取自回复类型。
     *
     * <p>示例：请求来自 {@code uvodp/low_speed/LSV.../inh/up}，回复类型 {@code inh-resp}，
     * 则回复到 {@code uvodp/low_speed/LSV.../inh-resp/down}。
     *
     * <p><b>为什么需要 fallback 参数？</b>接口文档存在一处<b>作用域不对称</b>：
     * <pre>
     *   请求 groute/up        ：uvodp/{enterpriseId}/groute/up          ← 云云（3 段）
     *   回复 groute-resp/down ：uvodp/{vehicleType}/{vin}/groute-resp/down ← 车云（5 段）
     * </pre>
     * 请求 Topic 里没有车辆身份，单靠镜像无法构造回复 Topic（会因缺少 vehicleType/vin
     * 直接抛异常，导致车端收不到 ACK 而反复重发）。因此当回复类型的作用域与请求不同时，
     * 回退使用「报文体内携带的 VIN」来定位车辆；车端类型报文里没有，
     * 只能取默认值 {@link #VEHICLE_TYPE_LOW_SPEED}。
     *
     * <p>该取舍的影响有限：groute-resp 仅是 ACK，最坏情况是 high_speed 车型收不到 ACK
     * 而按其重发策略重试若干次后放弃。已在本项目《文档缺陷与澄清清单》中登记，
     * 若监管平台确认该 Topic 应为云云作用域，只需把 {@link MqttType#GROUTE_RESP} 的
     * 作用域改回 {@code ENTERPRISE} 即可，本方法无需再改。
     *
     * @param request              请求报文的 Topic 元信息
     * @param ackType              回复报文类型
     * @param fallbackVin          请求 Topic 中无 VIN 时的回退值（通常取自报文体）
     * @param fallbackVehicleType  请求 Topic 中无车端类型时的回退值，为空则用默认值
     */
    public String ackTopic(TopicInfo request, MqttType ackType,
                           String fallbackVin, String fallbackVehicleType) {
        if (request == null || ackType == null) {
            throw new IllegalArgumentException("请求元信息与回复类型均不能为空");
        }
        // 方向必须取自回复类型自身的协议方向，不能写死 DOWN：
        // 多数回复是下游（*-resp/down），但 mapehn 的请求本身是下行，
        // 其回复 mapehn-resp 按文档 6.1.4.1.1 是「上行」（uvodp/{enterpriseId}/mapehn-resp/up）。
        // 写死 DOWN 会让回复发到无人订阅的主题，监管平台收不到回复 → 持续重发 mapehn，
        // 表现为车端被重复下发地图增强数据。
        TopicDirection ackDirection = ackType.getNaturalDirection();
        if (ackType.getScope() == TopicScope.CAR) {
            String vin = StringUtils.hasText(request.vin()) ? request.vin() : fallbackVin;
            String vehicleType = StringUtils.hasText(request.vehicleType())
                    ? request.vehicleType()
                    : (StringUtils.hasText(fallbackVehicleType)
                            ? fallbackVehicleType : VEHICLE_TYPE_LOW_SPEED);
            return build(ackType, ackDirection, vehicleType, vin, null, null);
        }
        return build(ackType, ackDirection, null, null, request.enterpriseId(), null);
    }

    /**
     * 构造下行指令 Topic（平台 → 车端），默认按 5 段格式（与正文一致）。
     *
     * @param type        下行类型，如 {@link MqttType#ACCIDENT_MEDIA}
     * @param vehicleType 车端类型
     * @param vin         车辆 VIN
     */
    public String downstream(MqttType type, String vehicleType, String vin) {
        return build(type, TopicDirection.DOWN, vehicleType, vin, null, null);
    }

    /**
     * 生成订阅过滤器列表。
     *
     * <p>共 3 个过滤器即可覆盖全部下行与车云上行场景，避免为每种类型单独订阅（订阅数爆炸）：
     * <ol>
     *   <li>{@code uvodp/{enterpriseId}/+/down}：云云下行（任务/远驾/导航回复、地图增强）；</li>
     *   <li>{@code uvodp/+/+/+/down}：车云下行（事故媒体数据请求、各类回复）；</li>
     *   <li>{@code uvodp/+/+/+/up}：车云上行（企业自建网关/车端直连场景，受配置开关控制）。</li>
     * </ol>
     */
    public List<String> subscriptionTopics(String enterpriseId, boolean subscribeCarUplink) {
        requireText(enterpriseId, "enterpriseId");
        return subscribeCarUplink
                ? List.of(
                        TopicParser.PREFIX + "/" + enterpriseId + "/+/down",
                        TopicParser.PREFIX + "/+/+/+/down",
                        TopicParser.PREFIX + "/+/+/+/up")
                : List.of(
                        TopicParser.PREFIX + "/" + enterpriseId + "/+/down",
                        TopicParser.PREFIX + "/+/+/+/down");
    }

    private void requireText(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("构造 Topic 缺少必要字段：" + name);
        }
    }
}
