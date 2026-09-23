package com.genvict.dssad.cloud.mqtt.handler;

import com.genvict.dssad.cloud.domain.entity.MapBarrier;
import com.genvict.dssad.cloud.mqtt.dto.DownMessages;
import com.genvict.dssad.cloud.mqtt.model.MqttType;
import com.genvict.dssad.cloud.service.MapBarrierService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 地图增强信息处理器（文档 6.1.5.2.1，<b>监管平台下行</b>）。
 *
 * <p>处理流程：落库（幂等：{@code barrierId + 生效日期}）→ 标记已回执。
 * ACK 的实际发送由 Router 统一完成（本类型 {@code MAPEHN} 在协议字典中声明了
 * {@code mapehn-resp} 回复主题），因此这里不重复发送。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MapehnDownstreamHandler implements MessageHandler {

    private final MapBarrierService mapBarrierService;

    @Override
    public Set<MqttType> supportedTypes() {
        return Set.of(MqttType.MAPEHN);
    }

    @Override
    public void handle(MessageContext context) {
        DownMessages.Mapehn payload = context.payloadAs(DownMessages.Mapehn.class);
        context.requireText("barrierId", payload.barrierId());
        context.requireText("barrierType", payload.barrierType());

        MapBarrier barrier = mapBarrierService.onPush(payload);
        mapBarrierService.markAcked(barrier.getBarrierId());

        if ("21".equals(payload.barrierType()) && payload.speedLimit() == null) {
            log.warn("[地图增强] 限速区域未提供限速值 barrierId={}，前端将无法展示限速", payload.barrierId());
        }
    }
}
