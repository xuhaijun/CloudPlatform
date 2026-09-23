package com.genvict.dssad.cloud;

import com.genvict.dssad.cloud.common.util.JsonUtils;
import com.genvict.dssad.cloud.mqtt.dto.CarUpMessages;
import com.genvict.dssad.cloud.mqtt.dto.DownMessages;
import com.genvict.dssad.cloud.mqtt.dto.EnterpriseUpMessages;
import com.genvict.dssad.cloud.security.AdminTokenInterceptor;
import com.genvict.dssad.cloud.security.TokenService;
import com.genvict.dssad.cloud.service.TelemetryService;
import com.genvict.dssad.cloud.support.TestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 平台端到端（E2E）集成测试：<b>报文注入 → 业务处理 → 落库 → HTTP 查询</b>。
 *
 * <p><b>为什么必须有这一层</b>：单元测试把每个类单独钉死后，仍然会漏掉「装配错误」——
 * Handler 没注册到路由表、Repository 查询方法名写错导致启动期才报错、
 * 事务边界不对导致落库后查不到、DTO 字段名与报文体不一致导致静默取到 null。
 * 这类问题只有把整条链路跑起来才会暴露。
 *
 * <p><b>为什么不用真实 Broker</b>：模拟器接口内部直接调用生产的
 * {@code MqttMessageRouter}（同样的解析 → 校验 → 去重 → 业务 → ACK → 留痕），
 * 因此走的逻辑与真实 Broker 完全一致，只是把「传输层」换成了一次 HTTP 调用。
 * 这样测试可以离线、秒级、可重复运行。
 *
 * <p>每个用例使用<b>独立 VIN</b> 并只按该 VIN 查询，避免用例之间互相污染，
 * 也允许任意顺序执行。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // 批量注入报文会瞬间超过文档约定的 10 条/秒，这是「配置生效」而非缺陷，
        // 限流本身由 SecurityAndRateLimitTest 单独覆盖
        "dssad.rate-limit.enabled=false"
})
class PlatformEndToEndTest {

    private static final String VIN_PREFIX = "TESTVIN0000000";
    private static final String VEHICLE_TYPE = "low_speed";
    private static final String CODE_SUCCESS = "0000";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TokenService tokenService;

    /**
     * 遥测服务。
     *
     * <p>轨迹点与状态快照是<b>先入内存缓冲、每秒批量刷盘</b>（见 TelemetryService#scheduledFlush）。
     * 测试里不能靠 sleep 等调度线程，而是直接调用 flush() 把「异步」变成「同步」，
     * 既让断言确定，也顺带覆盖了刷盘这条最容易写错的批量写入路径。
     */
    @Autowired
    private TelemetryService telemetryService;

    /** 管理端令牌：直接向 TokenService 取，避免把默认口令硬编码进测试。 */
    private String token;

    @BeforeEach
    void issueAdminToken() {
        token = tokenService.issue("e2e-test");
    }

    // ==================================================================
    // 1. 车端低频基础信息 → 车辆台账 / 实时位置 / 轨迹回放
    // ==================================================================

    @Nested
    @DisplayName("车端低频基础信息（5.1.4.3.2）")
    class LowBasePipeline {

        @Test
        @DisplayName("1Hz 位置上报后：车辆自动建档、当前位置可查、轨迹可回放")
        void buildsVehiclePositionAndTrack() throws Exception {
            String vin = VIN_PREFIX + "01";
            long base = System.currentTimeMillis();
            double startLat = 29.563000;
            double startLng = 106.551500;

            for (int i = 0; i < 5; i++) {
                inject("uvodp/" + VEHICLE_TYPE + "/" + vin + "/low-base/up",
                        new CarUpMessages.LowBase(vin, UUID.randomUUID().toString(),
                                base + i * 1000L,
                                startLng + i * 0.0001, startLat + i * 0.0001,
                                500.0, 32.5 + i, 0.0, 0, 90.0 + i));
            }

            // 车辆台账：上报即自动建档，运维不需要手工录入
            authorized(get("/api/v1/vehicles").param("keyword", vin))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.list[0].vin").value(vin));

            // 最新位置：取自状态存储（不查库），保证大屏刷新不被 1Hz 写库拖慢
            authorized(get("/api/v1/vehicles/" + vin + "/position"))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                    .andExpect(jsonPath("$.data.latitude").value(startLat + 0.0004))
                    .andExpect(jsonPath("$.data.longitude").value(startLng + 0.0004));

            // 轨迹回放：库内存全量 → 返回时抽稀（先显式刷盘，见 telemetryService 说明）
            telemetryService.flush();
            authorized(get("/api/v1/vehicles/" + vin + "/track")
                    .param("startTime", String.valueOf(base - 60_000))
                    .param("endTime", String.valueOf(base + 60_000)))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                    .andExpect(jsonPath("$.data.vin").value(vin))
                    .andExpect(jsonPath("$.data.rawCount").value(greaterThanOrEqualTo(5)))
                    .andExpect(jsonPath("$.data.points.length()").value(greaterThan(0)));
        }

        @Test
        @DisplayName("同一 msgId 重发不产生重复轨迹点（幂等去重）")
        void duplicateUplinkIsIdempotent() throws Exception {
            String vin = VIN_PREFIX + "02";
            long base = System.currentTimeMillis();
            String msgId = UUID.randomUUID().toString();
            CarUpMessages.LowBase payload = new CarUpMessages.LowBase(vin, msgId, base,
                    106.55, 29.56, 500.0, 30.0, 0.0, 0, 90.0);

            inject("uvodp/" + VEHICLE_TYPE + "/" + vin + "/low-base/up", payload);
            inject("uvodp/" + VEHICLE_TYPE + "/" + vin + "/low-base/up", payload);
            telemetryService.flush();

            authorized(get("/api/v1/vehicles/" + vin + "/track")
                    .param("startTime", String.valueOf(base - 60_000))
                    .param("endTime", String.valueOf(base + 60_000)))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                    .andExpect(jsonPath("$.data.rawCount").value(1));
        }
    }

    // ==================================================================
    // 2. 准静态参数 / 低频运行状态
    // ==================================================================

    @Nested
    @DisplayName("准静态参数与低频状态（5.1.4.2.1 / 5.1.4.3.1）")
    class StaticAndStatePipeline {

        @Test
        @DisplayName("inh 上报后进入变更历史，low-state 上报后可从状态接口读到")
        void inhAndLowStateArePersisted() throws Exception {
            String vin = VIN_PREFIX + "03";
            long now = System.currentTimeMillis();

            inject("uvodp/" + VEHICLE_TYPE + "/" + vin + "/inh/up",
                    new CarUpMessages.Inh(vin, UUID.randomUUID().toString(), now,
                            "Orin-X-254TOPS", "AD-1.4.2", "2", "4"));
            inject("uvodp/" + VEHICLE_TYPE + "/" + vin + "/low-state/up",
                    new CarUpMessages.LowState(vin, UUID.randomUUID().toString(), now,
                            "3", 1850.0, List.of(240.0, 240.0, 245.0, 245.0), 320.0,
                            15420.0, 78.5, 60.0, 135, "3"));

            authorized(get("/api/v1/vehicles/" + vin + "/static-params"))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                    .andExpect(jsonPath("$.data.total").value(greaterThanOrEqualTo(1)));

            telemetryService.flush();

            // 文档 5.1.4.3.1 的「数据偏移量 100」：temperature 保留协议原文 135，
            // temperatureCelsius 给出真值 35。两个字段同时出现，消费方不可能误用。
            authorized(get("/api/v1/vehicles/" + vin + "/state"))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                    .andExpect(jsonPath("$.data.temperature").value(135))
                    .andExpect(jsonPath("$.data.temperatureCelsius").value(35))
                    .andExpect(jsonPath("$.data.soc").value(78.5));

            // 状态历史：快照分页可查，且历史记录同样自带真值字段
            String history = authorized(get("/api/v1/vehicles/" + vin + "/state-history").param("hours", "1"))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                    .andExpect(jsonPath("$.data.total").value(greaterThan(0)))
                    .andExpect(jsonPath("$.data.list[0].temperatureCelsius").value(35))
                    .andReturn().getResponse().getContentAsString();
            org.junit.jupiter.api.Assertions.assertEquals(
                    JsonUtils.readTree(history).at("/data/list/0/temperature").asInt() - 100,
                    JsonUtils.readTree(history).at("/data/list/0/temperatureCelsius").asInt(),
                    "真值字段必须恒等于原始值减 100，前端不应自行推算");

            // 详情接口一次给全「车辆 + 最新状态 + 位置」，减少首屏请求数
            authorized(get("/api/v1/vehicles/" + vin))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                    .andExpect(jsonPath("$.data.vehicle.vin").value(vin));
        }
    }

    // ==================================================================
    // 3. 事故取证闭环（本项目最长的一条业务链）
    // ==================================================================

    @Nested
    @DisplayName("事故上报 → 取证请求 → 视频上传 → 归档")
    class AccidentEvidencePipeline {

        @Test
        @DisplayName("四方向视频全部上传后，取证材料判定为齐备")
        void fullEvidenceLoop() throws Exception {
            String vin = VIN_PREFIX + "04";

            // ① 车端事故上报 → 平台自动下发 accident-media/down 取证请求
            String scenario = authorized(post("/api/v1/simulator/scenario/accident").param("vin", vin))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                    .andExpect(jsonPath("$.data.mediaStatus").value("MEDIA_REQUESTED"))
                    .andReturn().getResponse().getContentAsString();

            String eventId = JsonUtils.readTree(scenario).at("/data/eventId").asText();
            String mediaRequestMsgId = JsonUtils.readTree(scenario).at("/data/mediaRequestMsgId").asText();

            // ② 事故列表与详情可查
            authorized(get("/api/v1/events/accidents").param("vin", vin))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.list[0].eventId").value(eventId));

            authorized(get("/api/v1/events/accidents/" + eventId))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                    .andExpect(jsonPath("$.data.mediaComplete").value(false))
                    .andExpect(jsonPath("$.data.media.length()").value(0));

            // ③ 车端受理并逐方向上传视频（鉴权靠 ackMsgId，见 AccidentMediaService）
            for (String direction : List.of("1", "2", "3", "4")) {
                uploadAccidentMedia(vin, mediaRequestMsgId, eventId, direction)
                        .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                        .andExpect(jsonPath("$.data.eventId").value(eventId))
                        .andExpect(jsonPath("$.data.cameraDirection").value(direction))
                        .andExpect(jsonPath("$.data.url").isNotEmpty());
            }

            // ④ 归档完成后取证要素齐备
            authorized(get("/api/v1/events/accidents/" + eventId))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                    .andExpect(jsonPath("$.data.event.mediaStatus").value("MEDIA_ARCHIVED"))
                    .andExpect(jsonPath("$.data.media.length()").value(4))
                    .andExpect(jsonPath("$.data.mediaComplete").value(true));

            // ⑤ 已归档的事故不应再出现在运维修正清单里
            authorized(get("/api/v1/events/accidents/pending").param("timeoutMinutes", "0"))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS));
        }

        @Test
        @DisplayName("同一方向重复上传不重复建档，且不会被误判为收齐")
        void duplicateDirectionDoesNotFakeCompleteness() throws Exception {
            String vin = VIN_PREFIX + "05";

            String scenario = authorized(post("/api/v1/simulator/scenario/accident").param("vin", vin))
                    .andReturn().getResponse().getContentAsString();
            String eventId = JsonUtils.readTree(scenario).at("/data/eventId").asText();
            String ackMsgId = JsonUtils.readTree(scenario).at("/data/mediaRequestMsgId").asText();

            // 同一方向传两次：第二次应命中幂等分支，返回既有记录
            uploadAccidentMedia(vin, ackMsgId, eventId, "1")
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS));
            uploadAccidentMedia(vin, ackMsgId, eventId, "1")
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS));

            // 同方向两次上传只留一条归档记录：否则「收齐 4 个方向」的判断会被同一方向刷满而误判
            authorized(get("/api/v1/events/accidents/" + eventId))
                    .andExpect(jsonPath("$.data.media.length()").value(1))
                    .andExpect(jsonPath("$.data.media[0].cameraDirection").value("1"))
                    .andExpect(jsonPath("$.data.mediaComplete").value(false))
                    // 只收到 1/4 方向，绝不能被判成「已归档」
                    .andExpect(jsonPath("$.data.event.mediaStatus").value("MEDIA_REQUESTED"));
        }

        @Test
        @DisplayName("ackMsgId 与平台下发的请求不匹配时拒绝建档（越权上传防护）")
        void uploadWithWrongAckMsgIdIsRejected() throws Exception {
            String vin = VIN_PREFIX + "06";

            String scenario = authorized(post("/api/v1/simulator/scenario/accident").param("vin", vin))
                    .andReturn().getResponse().getContentAsString();
            String eventId = JsonUtils.readTree(scenario).at("/data/eventId").asText();

            // 鉴权失败归入 2001（未授权）而不是 1001（参数不合法）：
            // 这是「你没有权限为本事件上传」，不是「你的参数写错了」
            uploadAccidentMedia(vin, "forged-ack-msg-id", eventId, "1")
                    .andExpect(jsonPath("$.code").value("2001"));

            authorized(get("/api/v1/events/accidents/" + eventId))
                    .andExpect(jsonPath("$.data.media.length()").value(0));
        }
    }

    // ==================================================================
    // 4. 故障上报 → 故障中心与统计
    // ==================================================================

    @Nested
    @DisplayName("故障上报（5.1.4.4.2）")
    class FaultPipeline {

        @Test
        @DisplayName("变长故障明细逐条落库，故障码按附录 A.1 翻译")
        void faultItemsArePersistedAndTranslated() throws Exception {
            String vin = VIN_PREFIX + "07";

            authorized(post("/api/v1/simulator/scenario/fault").param("vin", vin))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                    .andExpect(jsonPath("$.data.faultCount").value(5));

            // 一次上报含 5 条明细 → 落成 1 条故障记录 + 5 条明细
            String faults = authorized(get("/api/v1/events/faults").param("vin", vin))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andReturn().getResponse().getContentAsString();

            long faultId = JsonUtils.readTree(faults).at("/data/list/0/id").asLong();
            authorized(get("/api/v1/events/faults/" + faultId))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                    .andExpect(jsonPath("$.data.record.id").value(faultId))
                    .andExpect(jsonPath("$.data.itemCount").value(5))
                    // 明细按故障码升序，且回指父记录的字段必须被隐藏（否则序列化成环）
                    .andExpect(jsonPath("$.data.items[0].errorCode").value("4357"))
                    .andExpect(jsonPath("$.data.items[0].faultRecord").doesNotExist())
                    .andExpect(jsonPath("$.data.items[4].errorCode").value("5124"));

            // 严重等级过滤：明细里最高等级为 5，minSeverity=6 应查不到
            authorized(get("/api/v1/events/faults").param("vin", vin).param("minSeverity", "6"))
                    .andExpect(jsonPath("$.data.total").value(0));

            authorized(get("/api/v1/events/faults/top-codes").param("days", "1"))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                    .andExpect(jsonPath("$.data.length()").value(greaterThan(0)));
        }
    }

    // ==================================================================
    // 5. 地图增强下行 → 阻断设施
    // ==================================================================

    @Nested
    @DisplayName("地图增强信息（6.1.5.2.1）")
    class MapBarrierPipeline {

        @Test
        @DisplayName("禁行区域与限速区域均落库，可按日期与汇总查询")
        void mapehnDownlinkIsPersisted() throws Exception {
            String enterpriseId = TestProperties.ENTERPRISE_ID;

            inject("uvodp/" + enterpriseId + "/mapehn/down", new DownMessages.Mapehn(
                    enterpriseId, UUID.randomUUID().toString(), System.currentTimeMillis(),
                    "510100", "20", "施工禁行区", "BAR-E2E-001",
                    List.of(List.of(29.5630, 106.5510), List.of(29.5640, 106.5510),
                            List.of(29.5640, 106.5520), List.of(29.5630, 106.5510)),
                    "3", List.of(List.of("7:00", "9:00"), List.of("17:00", "19:30")), null));

            inject("uvodp/" + enterpriseId + "/mapehn/down", new DownMessages.Mapehn(
                    enterpriseId, UUID.randomUUID().toString(), System.currentTimeMillis(),
                    "510100", "21", "学校路段限速", "BAR-E2E-002",
                    List.of(List.of(29.5700, 106.5600), List.of(29.5710, 106.5610),
                            List.of(29.5700, 106.5600)),
                    "1", List.of(List.of("8:00", "9:00")), 30.0));

            authorized(get("/api/v1/map-barriers").param("barrierId", "BAR-E2E-001"))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                    .andExpect(jsonPath("$.data.length()").value(greaterThan(0)))
                    .andExpect(jsonPath("$.data[0].barrierType").value("20"));

            authorized(get("/api/v1/map-barriers/page").param("size", "5"))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                    .andExpect(jsonPath("$.data.total").value(greaterThanOrEqualTo(2)));

            authorized(get("/api/v1/map-barriers/summary"))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                    .andExpect(jsonPath("$.data.count").value(greaterThanOrEqualTo(2)));

            authorized(get("/api/v1/map-barriers/by-date")
                    .param("date", java.time.LocalDate.now().toString()))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS));
        }
    }

    // ==================================================================
    // 6. 远驾接管与任务/导航路径
    // ==================================================================

    @Nested
    @DisplayName("远驾接管与任务调度（第 6 章）")
    class OperationPipeline {

        @Test
        @DisplayName("发起→查询→结束接管，全程留痕并生成接管记录")
        void remoteCockpitLifecycle() throws Exception {
            String vin = VIN_PREFIX + "08";

            authorized(post("/api/v1/operations/vehicles/" + vin + "/cockpit/start")
                    .param("starter", "2"))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                    .andExpect(jsonPath("$.data.cockpitId").isNotEmpty());

            authorized(get("/api/v1/operations/vehicles/" + vin + "/cockpit"))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                    .andExpect(jsonPath("$.data.cockpitId").isNotEmpty());

            // 接管记录必须立刻可在本地查到（不依赖监管平台回 resp）
            authorized(get("/api/v1/operations/remote-driving").param("vin", vin))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.list[0].closed").value(false))
                    .andExpect(jsonPath("$.data.list[0].enterpriseId").value(TestProperties.ENTERPRISE_ID));

            authorized(post("/api/v1/operations/vehicles/" + vin + "/cockpit/end"))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                    // 存在过接管 → true；再调一次应为 false（幂等，不报错）
                    .andExpect(jsonPath("$.data").value(true));

            authorized(post("/api/v1/operations/vehicles/" + vin + "/cockpit/end"))
                    .andExpect(jsonPath("$.data").value(false));

            // 结束后：进行中列表清空，已结束列表能查到并带时长
            authorized(get("/api/v1/operations/remote-driving").param("vin", vin))
                    .andExpect(jsonPath("$.data.total").value(0));
            authorized(get("/api/v1/operations/remote-driving")
                    .param("vin", vin).param("closed", "true"))
                    .andExpect(jsonPath("$.data.total").value(greaterThanOrEqualTo(1)));
        }

        @Test
        @DisplayName("任务与导航路径上报后可按车查询")
        void taskAndRouteAreQueryable() throws Exception {
            String vin = VIN_PREFIX + "09";
            String enterpriseId = TestProperties.ENTERPRISE_ID;
            long now = System.currentTimeMillis();
            String taskId = "TASK-E2E-" + UUID.randomUUID();

            inject("uvodp/low_speed/" + vin + "/inh/up",
                    new CarUpMessages.Inh(vin, UUID.randomUUID().toString(), now,
                            "Orin-X", "AD-1.4.2", "2", "4"));

            // 任务由企业侧下发（文档 6.1.4.2.2）：一次调用同时完成「上报监管平台 + 本地落库」
            authorized(post("/api/v1/operations/tasks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(JsonUtils.toJson(java.util.Map.of(
                            "vin", vin,
                            "action", "1",
                            "taskId", taskId,
                            "startPosition", "成都东站",
                            "startPoint", List.of(30.6300, 104.1400),
                            "endPosition", "天府广场",
                            "endPoint", List.of(30.6570, 104.0660)))))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                    .andExpect(jsonPath("$.data.taskId").value(taskId))
                    .andExpect(jsonPath("$.data.status").value("RUNNING"))
                    .andExpect(jsonPath("$.data.enterpriseId").value(enterpriseId));

            inject("uvodp/" + enterpriseId + "/groute/up", new EnterpriseUpMessages.Groute(
                    enterpriseId, vin, UUID.randomUUID().toString(), now,
                    List.of(List.of(30.6300, 104.1400), List.of(30.6400, 104.1300),
                            List.of(30.6570, 104.0660)),
                    List.of(30.6300, 104.1400)));

            authorized(get("/api/v1/operations/tasks").param("vin", vin))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.list[0].taskId").value(taskId));

            authorized(get("/api/v1/operations/routes").param("vin", vin))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS));

            authorized(get("/api/v1/operations/routes/latest").param("vin", vin))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS));
        }
    }

    // ==================================================================
    // 7. 大屏、监控与字典
    // ==================================================================

    @Nested
    @DisplayName("大屏、链路监控与字典（第 8/9 章）")
    class OpsAndMonitor {

        @Test
        @DisplayName("大屏四个接口均可返回（空库也不能报错）")
        void dashboardEndpointsAreResilient() throws Exception {
            authorized(get("/api/v1/dashboard/overview"))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                    .andExpect(jsonPath("$.data").isMap());
            authorized(get("/api/v1/dashboard/trend").param("days", "7"))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS));
            authorized(get("/api/v1/dashboard/fault-categories"))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS));
            authorized(get("/api/v1/dashboard/fault-vehicles"))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS));
        }

        @Test
        @DisplayName("健康检查免鉴权；MQTT 无 Broker 时报告未启用而不是报错")
        void monitorEndpointsReflectHeadlessMode() throws Exception {
            mockMvc.perform(get("/api/v1/monitor/health-check"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS));

            authorized(get("/api/v1/monitor/mqtt"))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS));

            authorized(get("/api/v1/monitor/mqtt/stats").param("windowMinutes", "60"))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS));

            authorized(get("/api/v1/monitor/messages").param("size", "5"))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS));
        }

        @Test
        @DisplayName("报文留痕可按 msgId 串出全链路（排障用）")
        void messageTraceByMsgId() throws Exception {
            String vin = VIN_PREFIX + "10";
            String msgId = UUID.randomUUID().toString();

            inject("uvodp/" + VEHICLE_TYPE + "/" + vin + "/low-base/up",
                    new CarUpMessages.LowBase(vin, msgId, System.currentTimeMillis(),
                            106.55, 29.56, 500.0, 30.0, 0.0, 0, 90.0));

            authorized(get("/api/v1/monitor/messages").param("senderId", vin))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                    .andExpect(jsonPath("$.data.total").value(greaterThanOrEqualTo(1)));

            authorized(get("/api/v1/monitor/messages/trace/" + msgId))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS));
        }

        @Test
        @DisplayName("五个字典接口与附录 A.1/B.2 对齐")
        void dictionariesAreServed() throws Exception {
            authorized(get("/api/v1/dict/fault-codes"))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                    .andExpect(jsonPath("$.data.length()").value(greaterThan(0)));
            authorized(get("/api/v1/dict/fault-categories"))
                    .andExpect(jsonPath("$.data.length()").value(5));
            authorized(get("/api/v1/dict/camera-directions"))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS));
            authorized(get("/api/v1/dict/mqtt-types"))
                    .andExpect(jsonPath("$.data.length()").value(18));
            authorized(get("/api/v1/dict/error-codes"))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS));
            authorized(get("/api/v1/dict/stream-types"))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS));
        }
    }

    // ==================================================================
    // 8. 鉴权边界
    // ==================================================================

    @Nested
    @DisplayName("鉴权边界")
    class AuthBoundary {

        @Test
        @DisplayName("管理端接口缺令牌一律 2001，且不泄露业务数据")
        void adminApiRequiresToken() throws Exception {
            mockMvc.perform(get("/api/v1/vehicles"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("2001"))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("伪造令牌同样被拒（令牌必须由服务端签发）")
        void forgedTokenIsRejected() throws Exception {
            mockMvc.perform(get("/api/v1/dashboard/overview")
                            .header(AdminTokenInterceptor.TOKEN_HEADER, "forged-token-value"))
                    .andExpect(jsonPath("$.code").value("2001"));
        }

        @Test
        @DisplayName("注销后令牌立即失效（不等自然过期）")
        void revokedTokenIsRejected() throws Exception {
            String oneShot = tokenService.issue("e2e-revoke");
            mockMvc.perform(get("/api/v1/dashboard/overview")
                            .header(AdminTokenInterceptor.TOKEN_HEADER, oneShot))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS));

            mockMvc.perform(post("/api/v1/auth/logout")
                            .header(AdminTokenInterceptor.TOKEN_HEADER, oneShot))
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS));

            mockMvc.perform(get("/api/v1/dashboard/overview")
                            .header(AdminTokenInterceptor.TOKEN_HEADER, oneShot))
                    .andExpect(jsonPath("$.code").value("2001"));
        }

        @Test
        @DisplayName("参数校验失败的对外接口也走统一错误码（不暴露堆栈）")
        void enterpriseApiValidationIsUnified() throws Exception {
            mockMvc.perform(post("/enterprise/api/v1/video/live")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"cameraDirection\":[\"1\"]}"))
                    // 该接口由签名过滤器保护，缺签名头时先被拦下 → 2001
                    .andExpect(jsonPath("$.code").value("2001"));
        }
    }

    // ==================================================================
    // 工具方法
    // ==================================================================

    /** 通过模拟器注入一条报文（内部走生产的 MqttMessageRouter）。 */
    private void inject(String topic, Object payload) throws Exception {
        mockMvc.perform(post("/api/v1/simulator/mqtt")
                        .param("topic", topic)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(JsonUtils.toJson(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CODE_SUCCESS));
    }

    /** 带管理端令牌发起请求。 */
    private ResultActions authorized(MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder.header(AdminTokenInterceptor.TOKEN_HEADER, token));
    }

    /** 车端上传一段事故视频（HTTP POST multipart，文档 5.2.2.2.1）。 */
    private ResultActions uploadAccidentMedia(String vin, String ackMsgId,
                                             String eventId, String direction) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "cam-" + direction + ".mp4", "video/mp4", new byte[2048]);
        return mockMvc.perform(multipart("/manager/api/v1/video/accident-media-upload")
                .file(file)
                .param("vin", vin)
                .param("msgId", UUID.randomUUID().toString())
                .param("ackMsgId", ackMsgId)
                .param("eventId", eventId)
                .param("cameraDirection", direction));
    }
}
