package com.genvict.dssad.cloud.mqtt.handler;

import com.genvict.dssad.cloud.mqtt.dto.CarUpMessages;
import com.genvict.dssad.cloud.mqtt.model.MqttType;
import com.genvict.dssad.cloud.service.VehicleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 车辆准静态参数处理器（文档 5.1.4.2.1）。
 *
 * <p>车端在<b>每次 MQTT 连接或重连完成后</b>上报一次，因此本处理器的执行频率
 * 与「网络稳定性」正相关：联调阶段车端频繁断连重连时会被高频触发。
 * 实现上保持「一次 UPDATE + 一次 INSERT」的固定开销，不做额外查询。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InhMessageHandler implements MessageHandler {

    private final VehicleService vehicleService;

    @Override
    public Set<MqttType> supportedTypes() {
        return Set.of(MqttType.INH);
    }

    @Override
    public void handle(MessageContext context) {
        CarUpMessages.Inh payload = context.payloadAs(CarUpMessages.Inh.class);
        context.requireText("vin", payload.vin());
        context.requireNumber("timestamp", payload.timestamp());

        vehicleService.applyStaticParam(payload.vin(), context.vehicleType(), payload);
        log.info("[准静态参数] vin={} 硬件版本={} 软件版本={} 通讯类型={} 时间同步={}",
                payload.vin(), payload.hwVersion(), payload.adVersion(),
                payload.comType(), payload.timeSync());
    }
}
