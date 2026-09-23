package com.genvict.dssad.cloud.mqtt.handler;

import com.genvict.dssad.cloud.mqtt.dto.DownMessages;
import com.genvict.dssad.cloud.mqtt.model.AckCode;
import com.genvict.dssad.cloud.mqtt.model.MqttType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 监管平台回复报文处理器（覆盖 5.1.5.1.x 与 6.1.5.1.x 的 {@code *-resp/down}）。
 *
 * <p>本处理器<b>只做结果判定与日志</b>，业务上不写库，原因：
 * <ul>
 *   <li><b>关闭重发登记</b>是这类报文的共性动作，已由 {@code MqttMessageRouter}
 *       在分发前统一完成，不需要各处理器重复实现；</li>
 *   <li>任务、远驾等业务数据在企业库中本就存在，监管平台的 ACK 只代表
 *       「合规通道投递成功」。若在此处回写业务状态，会把「企业业务」与
 *       「监管通道可用性」强耦合 —— 监管平台抖动就会阻塞企业侧的运营操作。</li>
 * </ul>
 * 因此这里仅记录成功确认，异常回复（{@code res=2/3}）由 Router 切换重发策略并告警。
 */
@Slf4j
@Component
public class EnterpriseAckResponseHandler implements MessageHandler {

    /** 本处理器覆盖的全部回复类型。 */
    private static final Set<MqttType> SUPPORTED = Set.of(
            MqttType.INH_RESP,
            MqttType.GROUTE_RESP,
            MqttType.ACCIDENT_RESP,
            MqttType.ERROR_REPORT_RESP,
            MqttType.REMOTE_DRIVING_RESP,
            MqttType.TASK_RESP);

    @Override
    public Set<MqttType> supportedTypes() {
        return SUPPORTED;
    }

    @Override
    public void handle(MessageContext context) {
        DownMessages.Ack ack = context.payloadAs(DownMessages.Ack.class);
        context.requireText("ackMsgId", ack.ackMsgId());

        AckCode code = AckCode.fromCode(ack.res());
        if (code == null || !code.isSuccess()) {
            log.error("[回复] 平台返回异常 res={} ackMsgId={} topic={}",
                    ack.res(), ack.ackMsgId(), context.envelope().topic());
            return;
        }
        MqttType type = context.topic().type();
        log.info("[回复] 平台已确认 {} ackMsgId={} vin={}",
                type == null ? "未知类型" : type.getCode(), ack.ackMsgId(), ack.vin());
    }
}
