package com.genvict.dssad.cloud.mqtt.handler;

import com.genvict.dssad.cloud.common.util.TimeUtils;
import com.genvict.dssad.cloud.mqtt.dto.CarUpMessages;
import com.genvict.dssad.cloud.mqtt.model.MqttType;
import com.genvict.dssad.cloud.service.TelemetryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 车辆低频基础信息处理器（文档 5.1.4.3.2，<b>1s/次</b>，QoS 0）。
 *
 * <p>这是全平台最高频的报文（千车 ≈ 1000 条/秒）。处理器本身刻意保持极薄：
 * 只做范围校验与转发，真正的落库由 {@link TelemetryService} 的批量缓冲完成，
 * 确保 MQTT 消费线程不会被数据库 IO 拖住。
 *
 * <p>坐标合法性校验是必要的：GCJ-02 坐标系下中国范围内经度约 73~135、纬度约 3~54，
 * 明显越界的数据（如未初始化的 0,0 或经纬度写反导致的纬度 > 90）必须拒收，
 * 否则会污染轨迹表并让地图出现「穿越到国外」的诡异线条。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LowBaseMessageHandler implements MessageHandler {

    private static final double LNG_MIN = 60.0;
    private static final double LNG_MAX = 150.0;
    private static final double LAT_MIN = -10.0;
    private static final double LAT_MAX = 60.0;

    private final TelemetryService telemetryService;

    @Override
    public Set<MqttType> supportedTypes() {
        return Set.of(MqttType.LOW_BASE);
    }

    @Override
    public void handle(MessageContext context) {
        CarUpMessages.LowBase payload = context.payloadAs(CarUpMessages.LowBase.class);
        context.requireText("vin", payload.vin());
        context.requireNumber("timestamp", payload.timestamp());
        context.requireNumber("longitude", payload.longitude());
        context.requireNumber("latitude", payload.latitude());

        if (payload.longitude() < LNG_MIN || payload.longitude() > LNG_MAX
                || payload.latitude() < LAT_MIN || payload.latitude() > LAT_MAX) {
            // 注意：坐标越界按「消息有误」处理（抛 BizException），但本类型 QoS 0 且无回复主题，
            // Router 只会留痕不会发 ACK，属于预期行为。
            throw com.genvict.dssad.cloud.common.exception.BizException.paramInvalid(
                    "坐标越界：longitude=" + payload.longitude() + " latitude=" + payload.latitude());
        }

        telemetryService.onLowBase(payload.vin(), context.vehicleType(), payload);

        if (log.isTraceEnabled()) {
            log.trace("[低频基础] vin={} 时间={} 位置=({},{}) 速度={}km/h 模式={}",
                    payload.vin(), TimeUtils.format(payload.timestamp()),
                    payload.longitude(), payload.latitude(), payload.speed(), payload.drivingMode());
        }
    }
}
