package com.genvict.dssad.cloud.mqtt.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.genvict.dssad.cloud.common.util.TimeUtils;
import com.genvict.dssad.cloud.common.util.UuidUtils;
import com.genvict.dssad.cloud.mqtt.model.AckCode;
import com.genvict.dssad.cloud.mqtt.model.TopicInfo;
import com.genvict.dssad.cloud.mqtt.model.TopicScope;

/**
 * 统一 ACK 回复报文。
 *
 * <p>覆盖文档中全部「回复数据」结构（5.1.5.1.x、6.1.5.1.x），字段完全一致：
 * <pre>
 * { "vin": "...", "msgId": "云端消息编号UUID", "ackMsgId": "发送端消息ID",
 *   "timestamp": 1736840005000, "res": "1" }
 * </pre>
 * 云云场景下（6.1.5.1.x）额外包含 {@code enterpriseId}，通过 {@link JsonInclude} 在车云场景自动省略。
 *
 * <p>语义约定：{@code msgId} 是<b>回复方新生成</b>的消息编号；{@code ackMsgId} 回填
 * <b>请求方的 msgId</b>，对端据此完成请求-响应配对与重试去重。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AckMessage(
        String enterpriseId,
        String vin,
        String msgId,
        String ackMsgId,
        long timestamp,
        String res) {

    /** 生成成功确认回复。 */
    public static AckMessage confirm(TopicInfo request, String requestMsgId, String vin) {
        return reply(request, requestMsgId, vin, AckCode.CONFIRM);
    }

    /** 生成指定状态回复。 */
    public static AckMessage reply(TopicInfo request, String requestMsgId, String vin, AckCode code) {
        boolean enterpriseScope = request.scope() == TopicScope.ENTERPRISE;
        return new AckMessage(
                enterpriseScope ? request.enterpriseId() : null,
                vin != null ? vin : request.vin(),
                UuidUtils.random(),
                requestMsgId,
                TimeUtils.nowMillis(),
                code.getCode());
    }
}
