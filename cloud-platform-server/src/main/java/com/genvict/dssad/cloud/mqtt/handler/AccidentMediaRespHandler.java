package com.genvict.dssad.cloud.mqtt.handler;

import com.genvict.dssad.cloud.mqtt.dto.CarUpMessages;
import com.genvict.dssad.cloud.mqtt.model.MqttType;
import com.genvict.dssad.cloud.service.EventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 车端事故媒体数据请求回复处理器（文档 5.1.4.1.1）。
 *
 * <p>本条报文是<b>车端对平台下行请求的应用层 ACK</b>：
 * {@code ackMsgId} 即平台 {@code accident-media/down} 的 {@code msgId}。
 * 平台据此把事故状态推进到 {@code MEDIA_ACCEPTED}，并<b>关闭该下行请求的重发登记</b>
 * （关闭动作由 {@code MqttMessageRouter} 在进入本处理器之前完成，见 {@code handleAck}）。
 *
 * <p>注意：车端回 {@code res=1} 只代表「已受理」，视频是否真的上传成功要靠 HTTP 上传接口
 * 归档时再推进状态；因此不能在此处把状态直接置为完成。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccidentMediaRespHandler implements MessageHandler {

    private final EventService eventService;

    @Override
    public Set<MqttType> supportedTypes() {
        return Set.of(MqttType.ACCIDENT_MEDIA_RESP);
    }

    @Override
    public void handle(MessageContext context) {
        CarUpMessages.AccidentMediaResp payload = context.payloadAs(CarUpMessages.AccidentMediaResp.class);
        context.requireText("vin", payload.vin());
        context.requireText("ackMsgId", payload.ackMsgId());
        eventService.onAccidentMediaResp(payload.vin(), payload);
    }
}
