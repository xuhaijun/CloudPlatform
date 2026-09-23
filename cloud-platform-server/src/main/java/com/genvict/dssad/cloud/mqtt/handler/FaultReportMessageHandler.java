package com.genvict.dssad.cloud.mqtt.handler;

import com.genvict.dssad.cloud.mqtt.dto.CarUpMessages;
import com.genvict.dssad.cloud.mqtt.model.MqttType;
import com.genvict.dssad.cloud.service.EventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 车辆故障信息处理器（文档 5.1.4.4.2）。
 *
 * <p>一次报文可携带多条故障（{@code errorData} 数组），因此需要校验
 * {@code errorNum} 与数组长度是否一致 —— 不一致说明车端实现有误，
 * 此时<b>以数组实际长度为准</b>入库（数据完整性优先），但把差异记入日志，
 * 便于联调阶段快速定位车端缺陷。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FaultReportMessageHandler implements MessageHandler {

    private final EventService eventService;

    @Override
    public Set<MqttType> supportedTypes() {
        return Set.of(MqttType.ERROR_REPORT);
    }

    @Override
    public void handle(MessageContext context) {
        CarUpMessages.FaultReport payload = context.payloadAs(CarUpMessages.FaultReport.class);
        context.requireText("vin", payload.vin());
        context.requireNumber("timestamp", payload.timestamp());

        int actual = payload.errorData() == null ? 0 : payload.errorData().size();
        if (payload.errorNum() != null && payload.errorNum() != actual) {
            log.warn("[故障] errorNum({}) 与 errorData 实际长度({}) 不一致 vin={}，以实际数据为准",
                    payload.errorNum(), actual, payload.vin());
        }
        if (actual == 0) {
            // 无明细的故障报文没有业务价值，按「消息有误」处理让对端修正
            throw com.genvict.dssad.cloud.common.exception.BizException.paramInvalid("errorData 为空");
        }

        eventService.onFaultReport(payload.vin(), payload);
    }
}
