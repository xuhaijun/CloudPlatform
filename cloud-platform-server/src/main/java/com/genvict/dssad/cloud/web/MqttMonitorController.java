package com.genvict.dssad.cloud.web;

import com.genvict.dssad.cloud.common.api.ApiResponse;
import com.genvict.dssad.cloud.common.api.ErrorCode;
import com.genvict.dssad.cloud.common.cache.TwoLevelCacheManager;
import com.genvict.dssad.cloud.common.model.PageResult;
import com.genvict.dssad.cloud.config.AppProperties;
import com.genvict.dssad.cloud.domain.entity.MqttMessageLog;
import com.genvict.dssad.cloud.mqtt.core.InboundMessageQueue;
import com.genvict.dssad.cloud.mqtt.core.MqttAckRetryScheduler;
import com.genvict.dssad.cloud.mqtt.core.MqttChannelProbe;
import com.genvict.dssad.cloud.mqtt.core.MqttInboundWorker;
import com.genvict.dssad.cloud.mqtt.core.OfflineMessageQueue;
import com.genvict.dssad.cloud.mqtt.core.PahoMqttClientManager;
import com.genvict.dssad.cloud.mqtt.core.PendingAckRegistry;
import com.genvict.dssad.cloud.ratelimit.RateLimiter;
import com.genvict.dssad.cloud.service.MqttAuditService;
import com.genvict.dssad.cloud.service.TelemetryService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MQTT 链路监控与报文留痕查询。
 *
 * <p>这是平台运维的「黑匣子」页面，暴露三类信息：
 * <ol>
 *   <li><b>连接面</b>：Broker 连接状态、重连次数、发布成功/失败计数；</li>
 *   <li><b>管道面</b>：入站队列积压、消费线程处理量、离线补传队列积压、待 ACK 数量 ——
 *       这些指标一旦持续增长，说明对端处理不过来或链路异常，是排障的第一现场；</li>
 *   <li><b>数据面</b>：按 VIN/报文类型/处理结果检索留痕，并支持按 {@code msgId} 追溯全链路。</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v1/monitor")
@RequiredArgsConstructor
public class MqttMonitorController {

    private final PahoMqttClientManager clientManager;
    private final InboundMessageQueue inboundQueue;
    private final OfflineMessageQueue offlineQueue;
    private final PendingAckRegistry pendingAckRegistry;
    private final MqttAckRetryScheduler ackRetryScheduler;
    private final MqttInboundWorker inboundWorker;
    private final MqttAuditService auditService;
    private final TelemetryService telemetryService;
    private final MqttChannelProbe channelProbe;
    private final AppProperties properties;
    private final CacheManager cacheManager;
    private final RateLimiter rateLimiter;
    private final com.genvict.dssad.cloud.service.VehicleOnlineSweeper onlineSweeper;

    /**
     * 健康检查（供负载均衡/探针调用，不返回敏感信息）。
     *
     * <p>刻意<b>不</b>把 MQTT 断连算作不健康：平台在无 Broker 时仍能提供
     * 查询类接口，若因此被摘除流量，会导致历史数据也无法查看。
     *
     * <p>因此本接口回答的是「服务是否活着」；「数据通道是否通」由
     * {@link #mqttHealth()} 独立回答。两者必须一起探 —— 只看本接口会出现
     * 「探活全绿但一条车端数据都收不到」。
     */
    @GetMapping("/health-check")
    public ApiResponse<Map<String, Object>> healthCheck() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("mqttConnected", clientManager.isConnected());
        return ApiResponse.ok(data);
    }

    /**
     * MQTT 通道存活探针（供监控系统调用，无需令牌）。
     *
     * <p>HTTP 语义：UP / DISABLED → 200，DOWN → 503
     * （503 让「只会看状态码」的探针也能判定失败，不必解析 JSON）。
     * <b>未启用刻意返回 200</b>：「本环境是否允许未启用」是环境策略
     * （生产不允许、本地正常），交给告警规则判断，接口只如实报告事实。
     *
     * <p>只暴露粗粒度信号（状态 / 原因 / 连接态 / 重连次数）：
     * 队列积压与丢弃计数属内部运行数据，仍留在需要令牌的 {@link #mqttMetrics()}。
     */
    @GetMapping("/mqtt-health")
    public ResponseEntity<ApiResponse<Map<String, Object>>> mqttHealth() {
        Health health = channelProbe.check();
        Map<String, Object> details = health.getDetails();
        boolean enabled = Boolean.TRUE.equals(details.get("enabled"));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", enabled ? health.getStatus().getCode() : "DISABLED");
        data.put("reason", details.get("reason"));
        data.put("state", details.get("state"));
        data.put("reconnectAttempts", details.get("reconnectAttempts"));

        if (Status.UP.equals(health.getStatus())) {
            return ResponseEntity.ok(ApiResponse.ok(data));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiResponse<>(ErrorCode.MQTT_UNAVAILABLE.getCode(),
                        ErrorCode.MQTT_UNAVAILABLE.getMessage() + "：" + details.get("reason"), data));
    }

    /** MQTT 链路总览指标。 */
    @GetMapping("/mqtt")
    public ApiResponse<Map<String, Object>> mqttMetrics() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", properties.mqtt().enabled());
        data.put("brokerUrl", properties.mqtt().brokerUrl());
        data.put("clientId", properties.mqttClientId());
        data.put("state", clientManager.state());
        data.put("connected", clientManager.isConnected());
        data.put("reconnectAttempts", clientManager.reconnectAttempts());
        data.put("published", clientManager.publishedCount());
        data.put("publishFailed", clientManager.publishFailedCount());

        data.put("inboundQueueSize", inboundQueue.size());
        data.put("inboundDropped", inboundQueue.droppedCount());
        data.put("inboundProcessed", inboundWorker.processedCount());
        data.put("inboundFailed", inboundWorker.failedCount());
        data.put("consumerThreads", inboundWorker.threadCount());
        data.put("consumerVirtualThreads", inboundWorker.usesVirtualThreads());

        data.put("offlineQueueSize", offlineQueue.size());
        data.put("offlineDroppedByPressure", offlineQueue.droppedByPressure());
        data.put("offlineDroppedLowPriority", offlineQueue.droppedLowPriority());
        data.put("pendingAck", pendingAckRegistry.size());
        data.put("pendingRetry", ackRetryScheduler.pendingCount());

        data.put("telemetryBuffered", telemetryService.bufferedCount());
        data.put("telemetryDropped", telemetryService.droppedCount());
        data.put("trackPointsPersisted", telemetryService.persistedTrackPoints());
        data.put("stateSnapshotsPersisted", telemetryService.persistedStateSnapshots());

        // 车辆在线状态回落（P-02）：放在链路总览里，因为「在线数虚高」最常见的
        // 根因就是本任务没跑（如调度线程被阻塞）—— 掉线速率与链路健康同屏对照更直观
        Map<String, Object> onlineSweep = new LinkedHashMap<>();
        onlineSweep.put("sweepCount", onlineSweeper.sweepCount());
        onlineSweep.put("offlineTotal", onlineSweeper.offlineTotal());
        onlineSweep.put("lastOfflineCount", onlineSweeper.lastOfflineCount());
        Instant lastSweepAt = onlineSweeper.lastSweepAt();
        onlineSweep.put("lastSweepAt", lastSweepAt == null ? null : lastSweepAt.toEpochMilli());
        data.put("vehicleOnlineSweep", onlineSweep);
        return ApiResponse.ok(data);
    }

    /** 报文统计（默认近 5 分钟）。 */
    @GetMapping("/mqtt/stats")
    public ApiResponse<MqttAuditService.AuditStats> mqttStats(
            @RequestParam(defaultValue = "5") int windowMinutes) {
        return ApiResponse.ok(auditService.stats(windowMinutes));
    }

    /** 报文留痕分页查询。 */
    @GetMapping("/messages")
    public ApiResponse<PageResult<MqttMessageLog>> messages(
            @RequestParam(required = false) String senderId,
            @RequestParam(required = false) String mqttType,
            @RequestParam(required = false) String processStatus,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(auditService.page(senderId, mqttType, processStatus, page, size,
                properties.api().maxPageSize()));
    }

    /**
     * 按 {@code msgId} 追溯全链路。
     *
     * <p>返回顺序天然体现「上行 → 下行回复 → 重发」的时序（按接收时间升序），
     * 是排查「对端说没收到」类争议时最有力的证据。
     */
    @GetMapping("/messages/trace/{msgId}")
    public ApiResponse<List<MqttMessageLog>> trace(@PathVariable String msgId) {
        List<MqttMessageLog> logs = new ArrayList<>(auditService.traceByMsgId(msgId));
        logs.sort(java.util.Comparator.comparing(MqttMessageLog::getReceivedAt));
        return ApiResponse.ok(logs);
    }

    /**
     * 缓存与限流运行态。
     *
     * <p>为什么缓存指标必须可视化：缓存失效是<b>静默</b>的。命中率从 95% 掉到 0
     * 时所有接口依然返回正确结果，只是数据库 QPS 翻几倍 —— 表现为「大屏越来越慢」
     * 而不是任何一条错误日志。把 {@code hitRate} 与限流配置一起暴露，
     * 运维才能在用户感知之前发现缓存被穿透。
     */
    @GetMapping("/cache")
    public ApiResponse<Map<String, Object>> cacheMetrics() {
        Map<String, Object> data = new LinkedHashMap<>();
        if (cacheManager instanceof TwoLevelCacheManager twoLevel) {
            data.put("mode", twoLevel.isDistributed() ? "L1(Caffeine)+L2(Redis)" : "L1(Caffeine)");
            data.put("declaredCaches", twoLevel.declaredNames());
            data.put("caches", twoLevel.stats());
        } else {
            data.put("mode", cacheManager.getClass().getSimpleName());
            data.put("declaredCaches", cacheManager.getCacheNames());
            data.put("caches", List.of());
        }

        Map<String, Object> rateLimit = new LinkedHashMap<>();
        rateLimit.put("enabled", rateLimiter.config().enabled());
        rateLimit.put("httpPerMinute", rateLimiter.config().httpPerMinute());
        rateLimit.put("mqttPerSecond", rateLimiter.config().mqttPerSecond());
        // 算法标识显式暴露：固定窗口与滑动窗口的突发容忍度不同，
        // 压测时若不知道实际算法，很容易把「文档口径被突破」误判成缺陷
        rateLimit.put("algorithm", "sliding-window-counter");
        // 触发计数（实例级：重启清零、多实例各自累计，全局视图由监控系统求和）。
        // 只有配置没有计数时，运维无法区分「配置了但没流量」和「一直在触发但没人看见」；
        // 触发次数持续 > 0 是「车端异常重试」或「阈值配小了」的第一信号。
        rateLimit.put("httpRejectedTotal", rateLimiter.httpRejectedCount());
        rateLimit.put("mqttPublishRejectedTotal", rateLimiter.mqttPublishRejectedCount());
        // 13 位毫秒时间戳（0 = 从未触发）。刻意用 long 而非 Instant：
        // Jackson 默认把 Instant 序列化成秒级时间戳（实测），会破坏「所有时间戳 13 位毫秒」的接口口径
        rateLimit.put("lastRejectedAt", rateLimiter.lastRejectedAtMillis());
        data.put("rateLimit", rateLimit);
        return ApiResponse.ok(data);
    }
}
