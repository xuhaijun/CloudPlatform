package com.genvict.dssad.cloud.ratelimit;

import com.genvict.dssad.cloud.common.api.ApiResponse;
import com.genvict.dssad.cloud.common.store.InMemoryStateStore;
import com.genvict.dssad.cloud.config.AppProperties;
import com.genvict.dssad.cloud.support.TestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 企业侧单车辆限流过滤器（{@link EnterpriseRateLimitFilter}）的<b>行为断言</b>测试。
 *
 * <p>这是 {@code T-02} 点名缺失的那类测试：此前 {@code SlidingWindowRateLimiterTest}
 * 与 {@code SecurityAndRateLimitTest} 测的都是<b>限流算法本身</b>，「限流是否被接进
 * 请求链路」从未被验证——这正是 P0 缺陷 {@code F-01} 能在 200 个用例全绿的情况下
 * 潜伏至今的直接原因。本类不再测算法（那是 {@code SlidingWindowRateLimiterTest} 的职责），
 * 只测<b>链路行为</b>：请求打进来，配置里的「100 次/分钟」是否真的会拦人。
 *
 * <p><b>为什么用可控时钟</b>：滑动窗口的判定依赖「当前窗口 + 上一窗口加权」，
 * 若用真实系统时钟，测试执行期间恰好跨越分钟边界时，上一窗口的衰减权重会让
 * 「第 101 次应被拒」变成「第 150 次才拒」——用例随机失败且极难复现。
 * {@code StateStore#nowMillis()} 是接口特意留的时钟注入口（javadoc 原话：
 * 「便于实现方注入可控时钟做测试」），固定后所有请求落在同一窗口，判定完全确定。
 */
class EnterpriseRateLimitFilterTest {

    private static final String VIN_A = "LGAX2A130NH000001";
    private static final String VIN_B = "LGAX2A130NH000002";

    /** 固定时间戳（任意常量值，其 % 60000 = 30000，位于窗口中段，远离边界）。 */
    private static final long FIXED_NOW = 1_736_840_010_000L;

    /** 两个完整窗口的时长：跨过它之后，被填满的窗口已彻底滑出判定范围。 */
    private static final long TWO_WINDOWS_MILLIS = 120_000L;

    private FixedClockStateStore store;
    private MockMvc mockMvc;
    private int limit;

    @BeforeEach
    void setUp() {
        AppProperties properties = TestProperties.defaults();
        limit = properties.rateLimit().httpPerMinute();
        store = new FixedClockStateStore();
        mockMvc = buildMockMvc(properties, store);
    }

    private MockMvc buildMockMvc(AppProperties properties, FixedClockStateStore stateStore) {
        return MockMvcBuilders.standaloneSetup(new ProbeController())
                .addFilters(new EnterpriseRateLimitFilter(new RateLimiter(stateStore, properties)))
                .build();
    }

    private String body(String vin) {
        return "{\"vin\":\"" + vin + "\",\"cameraDirection\":[\"1\"],\"type\":\"1\"}";
    }

    /** 把 VIN_A 的本窗口配额全部用掉。 */
    private void exhaustVinA() throws Exception {
        for (int i = 1; i <= limit; i++) {
            mockMvc.perform(post("/enterprise/api/v1/probe").content(body(VIN_A))
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("0000"));
        }
    }

    // ==================== 行为断言：限流真的在拦 ====================

    @Test
    @DisplayName("同 VIN 连打 limit+5 次：前 100 次全放行，之后连续拒绝且业务码为 4001")
    void shouldRejectWhenVinExceedsLimitPerMinute() throws Exception {
        // 前 limit 次（100 次/分钟的额度内）：全部放行
        for (int i = 1; i <= limit; i++) {
            mockMvc.perform(post("/enterprise/api/v1/probe").content(body(VIN_A))
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("0000"));
        }
        // T-02 ② 的验收口径：同 VIN 连打 http-per-minute + 5 次 → 断言出现 4001
        for (int i = 1; i <= 5; i++) {
            mockMvc.perform(post("/enterprise/api/v1/probe").content(body(VIN_A))
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("4001"));
        }
    }

    @Test
    @DisplayName("反向对照：一辆车打满配额不影响其他车（限流键按 VIN 隔离）")
    void shouldNotAffectOtherVins() throws Exception {
        exhaustVinA();
        // 反向对照的意义：若实现把限流键写错（比如全局共一个计数器），
        // 这里会跟着被拒 —— 「拒绝所有」与「不限流」是两种完全不同的假修好
        mockMvc.perform(post("/enterprise/api/v1/probe").content(body(VIN_B))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0000"));
    }

    @Test
    @DisplayName("窗口滑过之后自动恢复放行（被拒请求不计数，不会被永久封死）")
    void shouldRecoverAfterWindowSlides() throws Exception {
        exhaustVinA();
        mockMvc.perform(post("/enterprise/api/v1/probe").content(body(VIN_A))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("4001"));

        store.advanceMillis(TWO_WINDOWS_MILLIS);
        mockMvc.perform(post("/enterprise/api/v1/probe").content(body(VIN_A))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("0000"));
    }

    // ==================== 边界与降级 ====================

    @Test
    @DisplayName("enabled=false 时完全不限流（limit+5 次全部放行）")
    void shouldNotLimitWhenDisabled() throws Exception {
        AppProperties properties = TestProperties.rateLimitDisabled();
        store = new FixedClockStateStore();
        mockMvc = buildMockMvc(properties, store);

        for (int i = 1; i <= limit + 5; i++) {
            mockMvc.perform(post("/enterprise/api/v1/probe").content(body(VIN_A))
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code").value("0000"));
        }
    }

    @Test
    @DisplayName("取不到 VIN（畸形 JSON / 无 vin 字段 / 空 body / 空白 VIN）一律放行")
    void shouldFailOpenWhenVinUnavailable() throws Exception {
        // 畸形 JSON：解析失败按放行处理（限流是保护性旁路，不能把脏 body 变成 500）
        mockMvc.perform(post("/enterprise/api/v1/probe").content("not-json{")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("0000"));
        // JSON 合法但没有 vin 字段：单企业维度按设计文档 6.4 明确不限流
        mockMvc.perform(post("/enterprise/api/v1/probe").content("{\"type\":\"1\"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("0000"));
        // 空 body
        mockMvc.perform(post("/enterprise/api/v1/probe").content("")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("0000"));
        // vin 为空白串
        mockMvc.perform(post("/enterprise/api/v1/probe").content("{\"vin\":\"   \"}")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("0000"));
    }

    @Test
    @DisplayName("multipart 上传跳过限流（与签名过滤器同款豁免，防滥用靠 MQTT 侧授权）")
    void shouldSkipMultipartUploads() throws Exception {
        mockMvc.perform(multipart("/enterprise/api/v1/probe-multipart"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0000"));
    }

    @Test
    @DisplayName("拒绝响应附带 Retry-After: 60 头（限流窗口 1 分钟）")
    void shouldIncludeRetryAfterHeaderWhenRejected() throws Exception {
        exhaustVinA();
        mockMvc.perform(post("/enterprise/api/v1/probe").content(body(VIN_A))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("4001"))
                .andExpect(header().string("Retry-After", "60"));
    }

    /** 限流探针端点：只验证「过滤器放行后请求到达了业务层」。 */
    @RestController
    static class ProbeController {

        @PostMapping("/enterprise/api/v1/probe")
        public ApiResponse<Void> probe() {
            return ApiResponse.ok();
        }

        @PostMapping(value = "/enterprise/api/v1/probe-multipart",
                consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        public ApiResponse<Void> probeMultipart() {
            return ApiResponse.ok();
        }
    }

    /** 可控时钟：{@code nowMillis()} 是 StateStore 接口特意留的测试注入口。 */
    private static final class FixedClockStateStore extends InMemoryStateStore {

        private long now = FIXED_NOW;

        void advanceMillis(long delta) {
            now += delta;
        }

        @Override
        public long nowMillis() {
            return now;
        }
    }
}
