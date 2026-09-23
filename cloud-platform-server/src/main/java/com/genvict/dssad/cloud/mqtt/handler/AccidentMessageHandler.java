package com.genvict.dssad.cloud.mqtt.handler;

import com.genvict.dssad.cloud.domain.entity.AccidentEvent;
import com.genvict.dssad.cloud.mqtt.dto.CarUpMessages;
import com.genvict.dssad.cloud.mqtt.model.MqttType;
import com.genvict.dssad.cloud.service.EventService;
import com.genvict.dssad.cloud.service.MqttCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 车辆事故信息处理器（文档 5.1.4.4.1）。
 *
 * <p>处理链（文档 3.3 + 5.1.5.2.1 的完整闭环）：
 * <pre>
 * 车端上报 accident/up
 *   → 落库事故事件（幂等，按 eventId）
 *   → 平台立即下发 accident-media/down 要求四方向视频（前/右/后/左）
 *   → 记录请求 msgId 用于与车端应答配对
 *   → 车端回 accident-media-resp/up（受理）
 *   → 车端 HTTP 上传视频 → 归档
 * </pre>
 *
 * <p><b>为什么在收到事故后「立即」请求媒体？</b>因为车端只保证保存「前 15s + 后 5s」的
 * 事件片段，越早请求越不容易因后续录制被覆盖而丢失证据。因此本处理器把「落库」与
 * 「下发媒体请求」放在同一次处理中（而不是交给异步巡检任务）。
 *
 * <p>媒体请求失败不影响事故落库：下发失败时报文会进入离线队列，重连后补传；
 * 同时事故状态停留在 {@code REPORTED}，可被运维巡检任务（{@code EventService#pendingAccidents}）
 * 重新发现并补请求。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccidentMessageHandler implements MessageHandler {

    /** 文档 3.3：至少需要车辆外部前/右/后/左 4 个方向的视频。 */
    private static final List<String> REQUIRED_DIRECTIONS = List.of("1", "2", "3", "4");

    private final EventService eventService;
    private final MqttCommandService mqttCommandService;

    @Override
    public Set<MqttType> supportedTypes() {
        return Set.of(MqttType.ACCIDENT);
    }

    @Override
    public void handle(MessageContext context) {
        CarUpMessages.Accident payload = context.payloadAs(CarUpMessages.Accident.class);
        context.requireText("vin", payload.vin());
        context.requireText("eventId", payload.eventId());
        context.requireNumber("timestamp", payload.timestamp());

        AccidentEvent event = eventService.onAccident(payload.vin(), context.vehicleType(), payload);

        // 仅在「新事件且尚未请求媒体」时下发，避免车端重发导致重复请求视频
        if (event.getMediaStatus() == AccidentEvent.MediaStatus.REPORTED) {
            String requestMsgId = mqttCommandService.requestAccidentMedia(
                    context.vehicleType(), payload.vin(), event.getEventId(), REQUIRED_DIRECTIONS);
            eventService.markMediaRequested(event.getEventId(), requestMsgId, REQUIRED_DIRECTIONS.size());
        } else {
            log.info("[事故] 事件已处于 {} 状态，跳过媒体请求 eventId={}",
                    event.getMediaStatus(), event.getEventId());
        }
    }
}
