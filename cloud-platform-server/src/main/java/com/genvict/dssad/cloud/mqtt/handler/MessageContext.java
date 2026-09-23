package com.genvict.dssad.cloud.mqtt.handler;

import com.genvict.dssad.cloud.common.exception.BizException;
import com.genvict.dssad.cloud.common.util.JsonUtils;
import com.genvict.dssad.cloud.mqtt.core.MqttEnvelope;
import com.genvict.dssad.cloud.mqtt.model.TopicInfo;
import org.springframework.util.StringUtils;

/**
 * 报文处理上下文。
 *
 * <p>把「解析后的 Topic 元信息 + 原始报文」打包传给 Handler，并提供：
 * <ul>
 *   <li>类型安全的反序列化（{@link #payloadAs}），避免每个 Handler 重复 writeValue/readValue；</li>
 *   <li>必填字段校验（{@link #requireText}），把「字段缺失」统一转成
 *       {@link BizException}（对应 {@code res=2} 消息有误）。</li>
 * </ul>
 *
 * @param envelope 报文快照
 * @param topic    解析后的 Topic 元信息
 */
public record MessageContext(MqttEnvelope envelope, TopicInfo topic) {

    /** 报文反序列化为指定 DTO。 */
    public <T> T payloadAs(Class<T> type) {
        return JsonUtils.parse(envelope.rawPayload(), type);
    }

    /** 车辆 VIN（车云作用域）。 */
    public String vin() {
        return topic.vin();
    }

    /** 企业 ID（云云作用域）。 */
    public String enterpriseId() {
        return topic.enterpriseId();
    }

    /** 车端类型：low_speed / high_speed。 */
    public String vehicleType() {
        return topic.vehicleType();
    }

    /** 报文编号。 */
    public String msgId() {
        return envelope.text("msgId");
    }

    /** 校验文本字段非空，缺失则抛出业务异常（→ 回复 res=2）。 */
    public void requireText(String field, String value) {
        if (!StringUtils.hasText(value)) {
            throw BizException.paramInvalid("缺少必填字段：" + field);
        }
    }

    /** 校验数值字段非空。 */
    public void requireNumber(String field, Number value) {
        if (value == null) {
            throw BizException.paramInvalid("缺少必填字段：" + field);
        }
    }
}
