package com.genvict.dssad.cloud.web;

import com.genvict.dssad.cloud.common.api.ApiResponse;
import com.genvict.dssad.cloud.common.exception.BizException;
import com.genvict.dssad.cloud.common.util.JsonUtils;
import com.genvict.dssad.cloud.common.util.TimeUtils;
import com.genvict.dssad.cloud.common.util.UuidUtils;
import com.genvict.dssad.cloud.domain.entity.AccidentEvent;
import com.genvict.dssad.cloud.mqtt.core.MqttEnvelope;
import com.genvict.dssad.cloud.mqtt.core.MqttMessageRouter;
import com.genvict.dssad.cloud.service.EventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报文注入模拟器（<b>仅本地开发/联调启用</b>）。
 *
 * <p><b>解决的问题</b>：平台的核心链路是 MQTT，而开发机上通常没有 Broker、
 * 更没有真车。若每改一处业务逻辑都要搭 Broker + 写脚本发包，迭代成本极高。
 * 本接口把「报文」抽象为一次 HTTP 调用，直接复用生产环境的
 * {@link MqttMessageRouter}（同样的 Topic 解析、校验、去重、ACK、留痕、
 * 状态机推进），因此<b>走模拟器验证过的逻辑，在真实 Broker 上行为一致</b>。
 *
 * <p><b>为什么默认关闭</b>：它能凭空造出事故事件与故障记录。
 * 通过 {@code dssad.simulator.enabled} 控制，生产配置（application-prod.yml）
 * 显式设为 {@code false}，并<b>不</b>在 prod 配置文件中提供开启方式说明，
 * 避免误开导致脏数据进入监管取证链路。
 *
 * <p>典型用法：
 * <pre>
 * # 1) 注入一条车端事故上报（会自动回 ACK，并下发事故媒体请求）
 * curl -X POST "http://127.0.0.1:8080/api/v1/simulator/scenario/accident?vin=TESTVIN0000000001"
 *
 * # 2) 用返回的 eventId + mediaRequestMsgId 调用上传接口，完成取证闭环
 * curl -X POST -F vin=... -F eventId=... -F ackMsgId=... -F cameraDirection=1 \
 *      -F file=@sample.mp4 http://127.0.0.1:8080/manager/api/v1/video/accident-media-upload
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/simulator")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dssad.simulator.enabled", havingValue = "true")
public class SimulatorController {

    private static final String DEFAULT_VEHICLE_TYPE = "low_speed";

    private final MqttMessageRouter messageRouter;
    private final EventService eventService;

    /**
     * 通用报文注入。
     *
     * @param topic   完整 Topic，例如 {@code uvodp/low_speed/TESTVIN0000000001/low-base/up}
     * @param payload 报文体（JSON 字符串）
     */
    @PostMapping("/mqtt")
    public ApiResponse<Map<String, Object>> inject(@RequestParam String topic,
                                                   @RequestBody String payload) {
        log.warn("[模拟器] 注入报文 topic={} size={}字节", topic, payload.length());
        long start = TimeUtils.nowMillis();
        messageRouter.route(MqttEnvelope.of(topic, payload.getBytes(StandardCharsets.UTF_8)));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("topic", topic);
        data.put("costMillis", TimeUtils.nowMillis() - start);
        data.put("note", "报文已按生产链路处理：解析 → 校验 → 去重 → 业务 → ACK → 留痕");
        return ApiResponse.ok(data);
    }

    /**
     * 一键构造一次完整的事故场景（车端事故上报）。
     *
     * <p>返回的 {@code mediaRequestMsgId} 就是后续调用
     * {@code /manager/api/v1/video/accident-media-upload} 时必须回传的 {@code ackMsgId}，
     * 有了它就能在本地把「事故上报 → 下发取证请求 → 视频上传 → 状态归档」
     * 这条最长的业务链完整跑通。
     */
    @PostMapping("/scenario/accident")
    public ApiResponse<Map<String, Object>> accidentScenario(
            @RequestParam String vin,
            @RequestParam(required = false, defaultValue = DEFAULT_VEHICLE_TYPE) String vehicleType) {

        String eventId = UuidUtils.randomWithDash();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vin", vin);
        body.put("msgId", UuidUtils.randomWithDash());
        body.put("timestamp", TimeUtils.nowMillis());
        body.put("eventId", eventId);
        body.put("accidentDesc", "模拟事故：测试路口左转与直行车辆碰撞");
        body.put("longitude", 106.551556);
        body.put("latitude", 29.563009);

        String topic = "uvodp/" + vehicleType + "/" + vin + "/accident/up";
        messageRouter.route(MqttEnvelope.of(topic, JsonUtils.toJson(body).getBytes(StandardCharsets.UTF_8)));

        AccidentEvent event = eventService.findAccident(eventId)
                .orElseThrow(() -> BizException.notFound("模拟事故未被处理，请检查 Topic 与必要字段"));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("eventId", event.getEventId());
        data.put("vin", event.getVin());
        data.put("mediaStatus", event.getMediaStatus().name());
        data.put("mediaRequestMsgId", event.getMediaRequestMsgId());
        data.put("nextStep", "把 mediaRequestMsgId 作为 ackMsgId 调用 "
                + "POST /manager/api/v1/video/accident-media-upload 上传各方向视频");
        return ApiResponse.ok(data);
    }

    /**
     * 一键构造一次故障上报（覆盖 5 类故障码，便于验证故障中心与统计）。
     */
    @PostMapping("/scenario/fault")
    public ApiResponse<Map<String, Object>> faultScenario(
            @RequestParam String vin,
            @RequestParam(required = false, defaultValue = DEFAULT_VEHICLE_TYPE) String vehicleType) {

        String msgId = UuidUtils.randomWithDash();
        long now = TimeUtils.nowMillis();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vin", vin);
        body.put("msgId", msgId);
        body.put("timestamp", now);
        body.put("errorNum", 5);
        body.put("errorData", List.of(
                faultItem("4613", "3", "GNSS 授时信号丢失", 1),
                faultItem("4865", "5", "CAN 总线连接断开", 2),
                faultItem("4868", "5", "自动驾驶计算平台温度过高", 1),
                faultItem("5124", "4", "云端任务执行超时", 1),
                faultItem("4357", "3", "右后轮胎压过低", 2)));
        body.put("currentPoint", List.of(29.563009, 106.551556));

        String topic = "uvodp/" + vehicleType + "/" + vin + "/error-report/up";
        messageRouter.route(MqttEnvelope.of(topic, JsonUtils.toJson(body).getBytes(StandardCharsets.UTF_8)));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("vin", vin);
        data.put("msgId", msgId);
        data.put("faultCount", 5);
        return ApiResponse.ok(data);
    }

    private Map<String, Object> faultItem(String code, String severity, String reason, int count) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("errorCode", code);
        item.put("errorSeverity", severity);
        item.put("errorReason", reason);
        item.put("errorTimestamp", TimeUtils.nowMillis());
        item.put("msgCount", count);
        return item;
    }
}
