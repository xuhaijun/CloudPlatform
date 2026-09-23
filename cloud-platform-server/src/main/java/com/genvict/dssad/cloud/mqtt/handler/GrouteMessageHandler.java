package com.genvict.dssad.cloud.mqtt.handler;

import com.genvict.dssad.cloud.mqtt.dto.EnterpriseUpMessages;
import com.genvict.dssad.cloud.mqtt.model.MqttType;
import com.genvict.dssad.cloud.service.OperationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 车端导航路径处理器（文档 6.1.4.2.3）。
 *
 * <p>报文特点是「大」：一次可能携带数千个路径点，接近 256KB 的单条报文上限（文档 9.2）。
 * 因此：
 * <ul>
 *   <li>不做逐点校验（点数多、校验收益低），只校验必要字段与点结构；</li>
 *   <li>整包 JSON 存 TEXT，冗余起终点与点数，避免拆表带来的存储放大。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GrouteMessageHandler implements MessageHandler {

    private final OperationService operationService;

    @Override
    public Set<MqttType> supportedTypes() {
        return Set.of(MqttType.GROUTE);
    }

    @Override
    public void handle(MessageContext context) {
        EnterpriseUpMessages.Groute payload = context.payloadAs(EnterpriseUpMessages.Groute.class);
        context.requireText("vin", payload.vin());
        context.requireNumber("timestamp", payload.timestamp());
        if (payload.pathPoints() == null || payload.pathPoints().isEmpty()) {
            throw com.genvict.dssad.cloud.common.exception.BizException
                    .paramInvalid("pathPoints 不能为空");
        }
        operationService.onGroute(payload);
    }
}
