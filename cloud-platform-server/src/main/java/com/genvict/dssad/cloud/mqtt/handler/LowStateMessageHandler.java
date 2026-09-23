package com.genvict.dssad.cloud.mqtt.handler;

import com.genvict.dssad.cloud.mqtt.dto.CarUpMessages;
import com.genvict.dssad.cloud.mqtt.model.MqttType;
import com.genvict.dssad.cloud.service.TelemetryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 车辆低频运行状态处理器（文档 5.1.4.3.1，60s/次，QoS 0）。
 *
 * <p>该报文<b>没有 ACK</b>（协议未定义回复主题），因此平台不做可靠投递保证；
 * 数据缺失属正常现象，不应据此告警。字段全部按「可为空」处理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LowStateMessageHandler implements MessageHandler {

    private final TelemetryService telemetryService;

    @Override
    public Set<MqttType> supportedTypes() {
        return Set.of(MqttType.LOW_STATE);
    }

    @Override
    public void handle(MessageContext context) {
        CarUpMessages.LowState payload = context.payloadAs(CarUpMessages.LowState.class);
        context.requireText("vin", payload.vin());
        context.requireNumber("timestamp", payload.timestamp());

        // 温度字段是偏移量（上报值 - 100 = 摄氏度），这里只做日志换算，库内保留原始值以便与车端对账
        if (log.isDebugEnabled() && payload.temperature() != null) {
            log.debug("[低频状态] vin={} SOC={}% 续航={}km 电池温度={}℃",
                    payload.vin(), payload.soc(), payload.endurance(), payload.temperature() - 100);
        }
        telemetryService.onLowState(payload.vin(), payload);
    }
}
