package com.genvict.dssad.cloud.web;

import com.genvict.dssad.cloud.common.exception.BizException;
import com.genvict.dssad.cloud.config.AppProperties;
import com.genvict.dssad.cloud.config.WebConfig;
import com.genvict.dssad.cloud.security.ActuatorIpWhitelistFilter;
import com.genvict.dssad.cloud.security.AdminTokenInterceptor;
import com.genvict.dssad.cloud.security.EnterpriseSignatureFilter;
import com.genvict.dssad.cloud.service.MediaService;
import com.genvict.dssad.cloud.service.RemoteCockpitService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 企业云端对外接口（文档 6.3）契约测试。
 *
 * <p>这组测试的保护目标不是「代码有没有写错」，而是<b>对外契约有没有被悄悄改坏</b>：
 * 监管平台是按文档字段名硬编码解析的，一旦 {@code cameraDirection} 被改成
 * {@code camera_direction}、或成功码从 {@code 0000} 变成 {@code 200}，
 * 本平台单测与自测全部通过，但联调现场会直接失败，且极难定位。
 * 因此这里对「路径 + 方法 + 字段名 + 错误码」逐条钉死。
 *
 * <p>采用 {@code @WebMvcTest} 切片而非 {@code @SpringBootTest}：只加载 Web 层，
 * 用真实 Jackson / 真实校验器 / 真实 {@code GlobalExceptionHandler}，
 * 不启动数据库与 MQTT，单个测试类秒级完成。
 *
 * <p>排除 {@link WebConfig} 与 {@link EnterpriseSignatureFilter} 的原因：
 * 两者是「平台自身管理接口」的安全设施，需要额外的 Bean 依赖；
 * 对外接口的签名鉴权由独立的 {@code EnterpriseSignatureFilterTest} 单独覆盖，
 * 避免把「契约」与「鉴权」两件事耦在同一个上下文里。
 * {@link AdminTokenInterceptor} 会被 {@code @WebMvcTest} 当作 HandlerInterceptor 自动纳入，
 * 但它同样属于管理接口范畴（且依赖 {@code TokenService}），一并排除。
 *
 * <p>{@link ActuatorIpWhitelistFilter} 同理<b>必须</b>排除：{@code @WebMvcTest} 会把
 * {@code Filter} 类型的 bean 一并纳入，而它依赖 {@code @ConfigurationProperties} 的
 * {@code AppProperties}（切片上下文不加载该 bean）—— 不排除则整个上下文都起不来，
 * 表现为本类<b>所有</b>用例集体报 {@code Failed to load ApplicationContext}。
 * 该过滤器的行为由 {@code ActuatorAccessControlTest} 单独覆盖。
 * {@link com.genvict.dssad.cloud.ratelimit.EnterpriseRateLimitFilter} 同因同果
 * （依赖 {@code RateLimiter} → {@code AppProperties}），一并排除；
 * 其行为由 {@code EnterpriseRateLimitFilterTest} 用真实限流器覆盖。
 */
@WebMvcTest(controllers = EnterpriseApiController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {WebConfig.class, EnterpriseSignatureFilter.class, AdminTokenInterceptor.class,
                        ActuatorIpWhitelistFilter.class,
                        com.genvict.dssad.cloud.ratelimit.EnterpriseRateLimitFilter.class}))
@ActiveProfiles("test")
class EnterpriseApiControllerTest {

    /** 统一响应体中的成功码（文档 7.1）。 */
    private static final String CODE_SUCCESS = "0000";
    /** 统一响应体中的参数错误码。 */
    private static final String CODE_PARAM_INVALID = "1001";

    private static final String VIN = "LGAX2A130NH000001";
    private static final String BASE = "/enterprise/api/v1";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MediaService mediaService;

    @MockitoBean
    private RemoteCockpitService remoteCockpitService;

    // ==================================================================
    // 6.3.2.1.1 实时视频地址获取
    // ==================================================================

    @Nested
    @DisplayName("6.3.2.1.1 实时视频地址获取")
    class LiveVideo {

        @Test
        @DisplayName("正常请求：返回 0000 与按方向展开的拉流地址数组")
        void shouldReturnStreamUrlsPerDirection() throws Exception {
            given(mediaService.liveVideo(VIN, List.of("1", "3"), "2")).willReturn(List.of(
                    new MediaService.LiveVideo("1", "前向",
                            "http://srs:8080/live/" + VIN + "_1.flv", "flv"),
                    new MediaService.LiveVideo("3", "后向",
                            "http://srs:8080/live/" + VIN + "_3.flv", "flv")));

            mockMvc.perform(post(BASE + "/video/live")
                            .contentType(APPLICATION_JSON)
                            .content("{\"vin\":\"" + VIN + "\",\"msgId\":\"msg-1001\","
                                    + "\"cameraDirection\":[\"1\",\"3\"],\"type\":\"2\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                    .andExpect(jsonPath("$.message").value("成功"))
                    .andExpect(jsonPath("$.data.vin").value(VIN))
                    .andExpect(jsonPath("$.data.video.length()").value(2))
                    // 字段名逐字对齐文档，禁止出现 snake_case
                    .andExpect(jsonPath("$.data.video[0].cameraDirection").value("1"))
                    .andExpect(jsonPath("$.data.video[0].url")
                            .value("http://srs:8080/live/" + VIN + "_1.flv"))
                    .andExpect(jsonPath("$.data.video[0].type").value("flv"))
                    .andExpect(jsonPath("$.data.video[0].camera_direction").doesNotExist())
                    .andExpect(jsonPath("$.data.video[1].cameraDirection").value("3"));
        }

        @Test
        @DisplayName("参数按原样透传给服务层：不做方向补全、不做类型改写")
        void shouldPassRequestThroughToService() throws Exception {
            given(mediaService.liveVideo(any(), anyList(), any())).willReturn(List.of());

            mockMvc.perform(post(BASE + "/video/live")
                            .contentType(APPLICATION_JSON)
                            .content("{\"vin\":\"" + VIN + "\",\"cameraDirection\":[\"2\"],"
                                    + "\"type\":\"1\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                    .andExpect(jsonPath("$.data.video.length()").value(0));

            verify(mediaService).liveVideo(VIN, List.of("2"), "1");
        }

        @Test
        @DisplayName("vin 为空：1001 且消息指明字段名，不落到 9999")
        void shouldRejectBlankVin() throws Exception {
            mockMvc.perform(post(BASE + "/video/live")
                            .contentType(APPLICATION_JSON)
                            .content("{\"vin\":\"  \",\"cameraDirection\":[\"1\"]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CODE_PARAM_INVALID))
                    .andExpect(jsonPath("$.message").value(
                            containsString("vin")))
                    .andExpect(jsonPath("$.data").doesNotExist());

            verifyNoInteractions(mediaService);
        }

        @Test
        @DisplayName("cameraDirection 缺失：1001（文档标注为必填数组）")
        void shouldRejectMissingCameraDirection() throws Exception {
            mockMvc.perform(post(BASE + "/video/live")
                            .contentType(APPLICATION_JSON)
                            .content("{\"vin\":\"" + VIN + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CODE_PARAM_INVALID))
                    .andExpect(jsonPath("$.message").value(
                            containsString("cameraDirection")));

            verifyNoInteractions(mediaService);
        }

        @Test
        @DisplayName("请求体不是合法 JSON：1001 而不是 9999")
        void shouldRejectMalformedJson() throws Exception {
            mockMvc.perform(post(BASE + "/video/live")
                            .contentType(APPLICATION_JSON)
                            .content("{\"vin\":"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CODE_PARAM_INVALID))
                    .andExpect(jsonPath("$.message").value("请求体不是合法的JSON"));

            verifyNoInteractions(mediaService);
        }

        @Test
        @DisplayName("服务层业务异常：错误码原样透传，不被兜底成 9999")
        void shouldPropagateBizException() throws Exception {
            willThrow(BizException.paramInvalid("非法的摄像头方向：9"))
                    .given(mediaService).liveVideo(any(), anyList(), any());

            mockMvc.perform(post(BASE + "/video/live")
                            .contentType(APPLICATION_JSON)
                            .content("{\"vin\":\"" + VIN + "\",\"cameraDirection\":[\"9\"]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CODE_PARAM_INVALID))
                    .andExpect(jsonPath("$.message").value("非法的摄像头方向：9"));
        }

        @Test
        @DisplayName("HTTP 方法不是 POST：1001（文档约定仅 POST）")
        void shouldRejectGetMethod() throws Exception {
            mockMvc.perform(get(BASE + "/video/live"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CODE_PARAM_INVALID))
                    .andExpect(jsonPath("$.message").value(
                            containsString("不支持的请求方法")));
        }
    }

    // ==================================================================
    // 6.3.2.1.2 历史视频获取
    // ==================================================================

    @Nested
    @DisplayName("6.3.2.1.2 历史视频获取")
    class HistoryVideo {

        @Test
        @DisplayName("时间文本按东八区解析后以毫秒传入服务层")
        void shouldParseTimeTextAsUtcPlus8() throws Exception {
            given(mediaService.historyVideo(any(), any(), anyLong(), anyLong()))
                    .willReturn(List.of());

            mockMvc.perform(post(BASE + "/video/history")
                            .contentType(APPLICATION_JSON)
                            .content("{\"vin\":\"" + VIN + "\",\"msgId\":\"msg-1002\","
                                    + "\"cameraDirection\":\"1\","
                                    + "\"startTime\":\"2026-09-22 10:00:00\","
                                    + "\"endTime\":\"2026-09-22 11:00:00\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                    .andExpect(jsonPath("$.data.vin").value(VIN))
                    .andExpect(jsonPath("$.data.video.length()").value(0));

            // 期望值独立计算（东八区 UTC+8），不复制被测实现的常量，避免"改错一起改"
            long expectedStart = LocalDateTime.of(2026, 9, 22, 10, 0, 0)
                    .atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
            long expectedEnd = LocalDateTime.of(2026, 9, 22, 11, 0, 0)
                    .atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();

            // 断言「解析结果」而不是「解析过程」：只要毫秒值正确，实现换成任何解析器都成立
            verify(mediaService).historyVideo(eq(VIN), eq("1"),
                    eq(expectedStart), eq(expectedEnd));
        }

        @Test
        @DisplayName("返回体字段名与文档一致：video[].url/type/startTime/endTime")
        void shouldSerializeHistoryItemFields() throws Exception {
            given(mediaService.historyVideo(any(), any(), anyLong(), anyLong()))
                    .willReturn(List.of(new MediaService.HistoryVideo(
                            "1", "前向",
                            "http://127.0.0.1:8080/media/history/" + VIN + "_1.mp4",
                            "mp4",
                            "2026-09-22 10:00:00", "2026-09-22 10:05:00",
                            1_758_506_400_000L, 1_758_506_700_000L, 10_485_760L)));

            mockMvc.perform(post(BASE + "/video/history")
                            .contentType(APPLICATION_JSON)
                            .content("{\"vin\":\"" + VIN + "\",\"cameraDirection\":\"1\","
                                    + "\"startTime\":\"2026-09-22 00:00:00\","
                                    + "\"endTime\":\"2026-09-23 00:00:00\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.video[0].cameraDirection").value("1"))
                    .andExpect(jsonPath("$.data.video[0].type").value("mp4"))
                    .andExpect(jsonPath("$.data.video[0].startTime").value("2026-09-22 10:00:00"))
                    .andExpect(jsonPath("$.data.video[0].endTime").value("2026-09-22 10:05:00"))
                    .andExpect(jsonPath("$.data.video[0].url")
                            .value("http://127.0.0.1:8080/media/history/" + VIN + "_1.mp4"));
        }

        @Test
        @DisplayName("startTime 格式非法：1001 且指明字段，不得落到 9999")
        void shouldRejectMalformedStartTime() throws Exception {
            mockMvc.perform(post(BASE + "/video/history")
                            .contentType(APPLICATION_JSON)
                            .content("{\"vin\":\"" + VIN + "\",\"cameraDirection\":\"1\","
                                    + "\"startTime\":\"2026/09/22 10:00\","
                                    + "\"endTime\":\"2026-09-22 11:00:00\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CODE_PARAM_INVALID))
                    .andExpect(jsonPath("$.message").value(
                            allOf(
                                    containsString("startTime"),
                                    containsString("格式不合法"))));

            verifyNoInteractions(mediaService);
        }

        @Test
        @DisplayName("endTime 缺失：1001（@NotBlank 先于时间解析生效）")
        void shouldRejectMissingEndTime() throws Exception {
            mockMvc.perform(post(BASE + "/video/history")
                            .contentType(APPLICATION_JSON)
                            .content("{\"vin\":\"" + VIN + "\",\"cameraDirection\":\"1\","
                                    + "\"startTime\":\"2026-09-22 10:00:00\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CODE_PARAM_INVALID))
                    .andExpect(jsonPath("$.message").value(
                            containsString("endTime")));

            verifyNoInteractions(mediaService);
        }

        @Test
        @DisplayName("跨度超限由服务层拦截：错误码透传")
        void shouldPropagateSpanLimitFromService() throws Exception {
            willThrow(BizException.paramInvalid("单次查询时间跨度过大，最大 168 小时"))
                    .given(mediaService).historyVideo(any(), any(), anyLong(), anyLong());

            mockMvc.perform(post(BASE + "/video/history")
                            .contentType(APPLICATION_JSON)
                            .content("{\"vin\":\"" + VIN + "\",\"cameraDirection\":\"1\","
                                    + "\"startTime\":\"2026-01-01 00:00:00\","
                                    + "\"endTime\":\"2026-12-31 00:00:00\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CODE_PARAM_INVALID))
                    .andExpect(jsonPath("$.message").value("单次查询时间跨度过大，最大 168 小时"));
        }
    }

    // ==================================================================
    // 6.3.2.2.1 远程驾驶接管发起
    // ==================================================================

    @Nested
    @DisplayName("6.3.2.2.1 远程驾驶接管发起")
    class RemoteCockpitStart {

        private static final String COCKPIT_ID = "YK-TEST-01";

        private AppProperties.RemoteDriving.Cockpit cockpit() {
            return new AppProperties.RemoteDriving.Cockpit(COCKPIT_ID, "测试驾驶员A",
                    "510100199001011234", "510100199001011", "13800000001");
        }

        @Test
        @DisplayName("正常请求：返回舱与驾驶员信息，并把车辆绑定到该舱")
        void shouldReturnCockpitAndBindVehicle() throws Exception {
            given(remoteCockpitService.startTakeover(VIN, "1")).willReturn(cockpit());

            mockMvc.perform(post(BASE + "/event/remote-cockpit-start")
                            .contentType(APPLICATION_JSON)
                            .content("{\"vin\":\"" + VIN + "\",\"starter\":\"1\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                    .andExpect(jsonPath("$.data.vin").value(VIN))
                    .andExpect(jsonPath("$.data.remoteCockpitId").value(COCKPIT_ID))
                    .andExpect(jsonPath("$.data.driver").value("测试驾驶员A"))
                    .andExpect(jsonPath("$.data.identityNumber").value("510100199001011234"))
                    .andExpect(jsonPath("$.data.driverLicenseNumber").value("510100199001011"))
                    .andExpect(jsonPath("$.data.mobile").value("13800000001"));

            // 绑定的必须是「实际分配到的舱」，而不是请求里带的任何值
            verify(remoteCockpitService).bindVehicle(VIN, COCKPIT_ID);
        }

        @Test
        @DisplayName("先校验发起人再分配资源：非法发起人不得占用远驾舱")
        void shouldValidateStarterBeforeAllocating() throws Exception {
            given(remoteCockpitService.startTakeover(any(), any())).willReturn(cockpit());

            mockMvc.perform(post(BASE + "/event/remote-cockpit-start")
                            .contentType(APPLICATION_JSON)
                            .content("{\"vin\":\"" + VIN + "\",\"starter\":\"1\"}"))
                    .andExpect(status().isOk());

            InOrder order = inOrder(remoteCockpitService);
            order.verify(remoteCockpitService).validateStarter("1");
            order.verify(remoteCockpitService).startTakeover(VIN, "1");
        }

        @Test
        @DisplayName("发起人非法：服务层抛 1001 时不得继续分配资源")
        void shouldStopWhenStarterInvalid() throws Exception {
            willThrow(BizException.paramInvalid("starter 取值必须为 1（交巡警）或 2（监管平台）"))
                    .given(remoteCockpitService).validateStarter("9");

            mockMvc.perform(post(BASE + "/event/remote-cockpit-start")
                            .contentType(APPLICATION_JSON)
                            .content("{\"vin\":\"" + VIN + "\",\"starter\":\"9\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CODE_PARAM_INVALID))
                    .andExpect(jsonPath("$.data").doesNotExist());

            verify(remoteCockpitService, never()).startTakeover(any(), any());
            verify(remoteCockpitService, never()).bindVehicle(any(), any());
        }

        @Test
        @DisplayName("starter 为空：1001（文档标注为必填）")
        void shouldRejectBlankStarter() throws Exception {
            mockMvc.perform(post(BASE + "/event/remote-cockpit-start")
                            .contentType(APPLICATION_JSON)
                            .content("{\"vin\":\"" + VIN + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CODE_PARAM_INVALID))
                    .andExpect(jsonPath("$.message").value(
                            containsString("starter")));

            verifyNoInteractions(remoteCockpitService);
        }
    }

    // ==================================================================
    // 结束接管（企业侧补充能力，文档 6.3 未定义）
    // ==================================================================

    @Nested
    @DisplayName("结束远程驾驶接管（企业侧补充接口）")
    class RemoteCockpitEnd {

        @Test
        @DisplayName("存在进行中的接管：data 返回 true")
        void shouldReturnTrueWhenTakeoverExisted() throws Exception {
            given(remoteCockpitService.endTakeover(VIN)).willReturn(true);

            mockMvc.perform(post(BASE + "/event/remote-cockpit-end")
                            .contentType(APPLICATION_JSON)
                            .content("{\"vin\":\"" + VIN + "\",\"starter\":\"1\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                    .andExpect(jsonPath("$.data").value(true));
        }

        @Test
        @DisplayName("原本无接管：幂等返回 false，不报错")
        void shouldReturnFalseWhenNoTakeover() throws Exception {
            given(remoteCockpitService.endTakeover(VIN)).willReturn(false);

            mockMvc.perform(post(BASE + "/event/remote-cockpit-end")
                            .contentType(APPLICATION_JSON)
                            .content("{\"vin\":\"" + VIN + "\",\"starter\":\"2\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CODE_SUCCESS))
                    .andExpect(jsonPath("$.data").value(false));
        }
    }
}
