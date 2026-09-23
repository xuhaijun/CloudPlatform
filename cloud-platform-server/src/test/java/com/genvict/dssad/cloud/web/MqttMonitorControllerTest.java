package com.genvict.dssad.cloud.web;

import com.genvict.dssad.cloud.config.AppProperties;
import com.genvict.dssad.cloud.config.WebConfig;
import com.genvict.dssad.cloud.mqtt.core.InboundMessageQueue;
import com.genvict.dssad.cloud.mqtt.core.MqttAckRetryScheduler;
import com.genvict.dssad.cloud.mqtt.core.MqttChannelProbe;
import com.genvict.dssad.cloud.mqtt.core.MqttInboundWorker;
import com.genvict.dssad.cloud.mqtt.core.OfflineMessageQueue;
import com.genvict.dssad.cloud.mqtt.core.PahoMqttClientManager;
import com.genvict.dssad.cloud.mqtt.core.PendingAckRegistry;
import com.genvict.dssad.cloud.ratelimit.RateLimiter;
import com.genvict.dssad.cloud.security.ActuatorIpWhitelistFilter;
import com.genvict.dssad.cloud.security.AdminTokenInterceptor;
import com.genvict.dssad.cloud.security.EnterpriseSignatureFilter;
import com.genvict.dssad.cloud.service.MqttAuditService;
import com.genvict.dssad.cloud.service.TelemetryService;
import com.genvict.dssad.cloud.service.VehicleOnlineSweeper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 监控端点（{@code /api/v1/monitor}）切片测试。
 *
 * <p>保护目标与 {@code EnterpriseApiControllerTest} 一致：监控端点是运维排障的入口，
 * 字段名（{@code httpRejectedTotal} / {@code vehicleOnlineSweep} 等）一旦被重命名，
 * 前端大屏与告警规则会静默失效 —— 因此把「字段存在 + 类型正确」钉死在契约里。
 *
 * <p>排除项同 {@code EnterpriseApiControllerTest}（安全过滤器与拦截器依赖
 * 切片上下文不具备的 bean，会导致整个上下文加载失败）。
 */
@WebMvcTest(controllers = MqttMonitorController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {WebConfig.class, EnterpriseSignatureFilter.class, AdminTokenInterceptor.class,
                        ActuatorIpWhitelistFilter.class,
                        com.genvict.dssad.cloud.ratelimit.EnterpriseRateLimitFilter.class}))
@ActiveProfiles("test")
class MqttMonitorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private PahoMqttClientManager clientManager;
    @MockitoBean private InboundMessageQueue inboundQueue;
    @MockitoBean private OfflineMessageQueue offlineQueue;
    @MockitoBean private PendingAckRegistry pendingAckRegistry;
    @MockitoBean private MqttAckRetryScheduler ackRetryScheduler;
    @MockitoBean private MqttInboundWorker inboundWorker;
    @MockitoBean private MqttAuditService auditService;
    @MockitoBean private TelemetryService telemetryService;
    @MockitoBean private MqttChannelProbe channelProbe;
    @MockitoBean private AppProperties properties;
    @MockitoBean private CacheManager cacheManager;
    @MockitoBean private RateLimiter rateLimiter;
    @MockitoBean private VehicleOnlineSweeper onlineSweeper;

    @Test
    @DisplayName("/monitor/cache：限流配置 + 触发计数 + 最近触发时间字段齐全")
    void cacheExposesRateLimitConfigAndCounters() throws Exception {
        given(rateLimiter.config()).willReturn(new AppProperties.RateLimit(true, 100, 10));
        given(rateLimiter.httpRejectedCount()).willReturn(42L);
        given(rateLimiter.mqttPublishRejectedCount()).willReturn(7L);
        given(rateLimiter.lastRejectedAtMillis()).willReturn(1_760_000_000_000L);
        given(cacheManager.getCacheNames()).willReturn(java.util.Set.of());

        mockMvc.perform(get("/api/v1/monitor/cache"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rateLimit.enabled").value(true))
                .andExpect(jsonPath("$.data.rateLimit.httpPerMinute").value(100))
                .andExpect(jsonPath("$.data.rateLimit.mqttPerSecond").value(10))
                .andExpect(jsonPath("$.data.rateLimit.algorithm").value("sliding-window-counter"))
                .andExpect(jsonPath("$.data.rateLimit.httpRejectedTotal").value(42))
                .andExpect(jsonPath("$.data.rateLimit.mqttPublishRejectedTotal").value(7))
                .andExpect(jsonPath("$.data.rateLimit.lastRejectedAt").value(1_760_000_000_000L));    }

    @Test
    @DisplayName("/monitor/cache：从未触发时 lastRejectedAt 输出为 null（non_null 全局策略下字段可省）")
    void cacheOmitsLastRejectedAtWhenNeverRejected() throws Exception {
        given(rateLimiter.config()).willReturn(new AppProperties.RateLimit(true, 100, 10));
        given(rateLimiter.httpRejectedCount()).willReturn(0L);
        given(rateLimiter.mqttPublishRejectedCount()).willReturn(0L);
        given(rateLimiter.lastRejectedAtMillis()).willReturn(0L);
        given(cacheManager.getCacheNames()).willReturn(java.util.Set.of());

        mockMvc.perform(get("/api/v1/monitor/cache"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rateLimit.httpRejectedTotal").value(0));
    }

    @Test
    @DisplayName("/monitor/mqtt：在线状态回落统计字段齐全（P-02 可观测）")
    void mqttExposesVehicleOnlineSweepStats() throws Exception {
        given(properties.mqtt()).willReturn(new AppProperties.Mqtt(
                false, "tcp://127.0.0.1:1883", "e_", "", "", 60, false, true,
                new AppProperties.Reconnect(2000, 60000, 2.0),
                new AppProperties.Retry(5000, 3, 3000, 5),
                10000, 4));
        given(properties.mqttClientId()).willReturn("e_DSSAD-ENT-0001");
        given(clientManager.state()).willReturn(com.genvict.dssad.cloud.mqtt.model.MqttConnectionState.DISCONNECTED);
        given(clientManager.reconnectAttempts()).willReturn(0);
        given(clientManager.publishedCount()).willReturn(0L);
        given(clientManager.publishFailedCount()).willReturn(0L);
        given(inboundQueue.size()).willReturn(0);
        given(inboundQueue.droppedCount()).willReturn(0L);
        given(inboundWorker.processedCount()).willReturn(0L);
        given(inboundWorker.failedCount()).willReturn(0L);
        given(inboundWorker.threadCount()).willReturn(4);
        given(inboundWorker.usesVirtualThreads()).willReturn(true);
        given(offlineQueue.size()).willReturn(0);
        given(offlineQueue.droppedByPressure()).willReturn(0L);
        given(offlineQueue.droppedLowPriority()).willReturn(0L);
        given(pendingAckRegistry.size()).willReturn(0);
        given(ackRetryScheduler.pendingCount()).willReturn(0);
        given(telemetryService.bufferedCount()).willReturn(0);
        given(telemetryService.droppedCount()).willReturn(0L);
        given(telemetryService.persistedTrackPoints()).willReturn(0L);
        given(telemetryService.persistedStateSnapshots()).willReturn(0L);
        given(onlineSweeper.sweepCount()).willReturn(120L);
        given(onlineSweeper.offlineTotal()).willReturn(36L);
        given(onlineSweeper.lastOfflineCount()).willReturn(2L);
        given(onlineSweeper.lastSweepAt()).willReturn(Instant.ofEpochMilli(1_760_000_000_000L));

        mockMvc.perform(get("/api/v1/monitor/mqtt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vehicleOnlineSweep.sweepCount").value(120))
                .andExpect(jsonPath("$.data.vehicleOnlineSweep.offlineTotal").value(36))
                .andExpect(jsonPath("$.data.vehicleOnlineSweep.lastOfflineCount").value(2))
                .andExpect(jsonPath("$.data.vehicleOnlineSweep.lastSweepAt").value(1_760_000_000_000L));
    }
}
