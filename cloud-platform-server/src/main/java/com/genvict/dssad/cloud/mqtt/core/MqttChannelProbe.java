package com.genvict.dssad.cloud.mqtt.core;

import com.genvict.dssad.cloud.config.AppProperties;
import com.genvict.dssad.cloud.mqtt.model.MqttConnectionState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * MQTT 通道存活探针 —— 把「收不到数据」变成可探活、可告警的信号。
 *
 * <p><b>为什么需要它：</b>
 * 平台的 liveness / readiness 与 {@code /api/v1/monitor/health-check} 在 Broker 断开时
 * <b>一律返回成功</b>。这是刻意设计：查询类接口在断链时仍应可用，否则历史数据也跟着看不了。
 * 但运维侧需要一个<b>只反映数据通道</b>的信号，否则会出现
 * 「所有探针全绿、监控面板 100% 可用，平台一条车端数据都收不到」这种只能靠人翻日志发现的故障。
 *
 * <p><b>为什么刻意<b>不</b>实现 {@code HealthIndicator}（实测结论，勿改回）：</b>
 * 最初本类实现 {@code HealthIndicator} 并挂到 health group {@code mqtt} 下，实测结果是
 * {@code /actuator/health}（聚合端点，HTTP 503）也跟着变红 —— Spring Boot 的聚合端点
 * <b>始终包含所有已注册的指示器</b>，健康组的 include 不会把它从默认组里摘出去。
 * 后果与设计意图正好相反：任何用 {@code /actuator/health} 做存活判定的编排会把实例摘流甚至重启，
 * 而 Broker 故障重启应用毫无用处（典型的重启风暴）。
 * 因此这里只提供「算状态」的能力，由业务探针端点
 * {@code GET /api/v1/monitor/mqtt-health} 决定 HTTP 语义（DOWN → 503），
 * 该端点与业务探针同源、已被 Nginx 的 {@code /api/} 规则代理，无需为此放开 {@code /actuator/}。
 *
 * <p><b>判定口径（刻意保持无状态）：</b>
 * <ul>
 *   <li>{@code dssad.mqtt.enabled=false} → UP（未启用不是故障，本地开发与单测即此态）；</li>
 *   <li>状态 {@code CONNECTED} → UP；</li>
 *   <li>其余（{@code DISCONNECTED / CONNECTING / RECONNECTING / FAILED}）→ DOWN，
 *       并在 {@code reason} 里区分「配置/凭据致命错误」与「链路断开」。</li>
 * </ul>
 * <b>瞬时重连也算 DOWN</b>：本探针只回答「此刻是否连通」，「持续多久才算故障」
 * 交给告警规则的持续时长（如 {@code for: 2m}）决定 —— 把计时器写进探针，
 * 它就变成有状态的黑盒，排障时还得先猜它的内部计时。
 */
@Component
public class MqttChannelProbe {

    private final AppProperties.Mqtt mqtt;
    private final PahoMqttClientManager clientManager;
    private final InboundMessageQueue inboundQueue;
    private final OfflineMessageQueue offlineQueue;
    private final PendingAckRegistry pendingAckRegistry;

    /**
     * 生产构造器。
     *
     * <p>必须显式标 {@code @Autowired}：本类为可测性多了一个接收 {@link AppProperties.Mqtt} 的
     * 包级构造器，Spring 面对多个候选构造器时不会「猜」，而是回落到无参构造器并报
     * {@code No default constructor found}（启动期才暴露，25 个 context 用例一起挂）。
     * 显式标注后语义才确定 —— 这也是「为可测性加构造器」必须付的代价。
     */
    @Autowired
    public MqttChannelProbe(AppProperties properties,
                            PahoMqttClientManager clientManager,
                            InboundMessageQueue inboundQueue,
                            OfflineMessageQueue offlineQueue,
                            PendingAckRegistry pendingAckRegistry) {
        this(properties.mqtt(), clientManager, inboundQueue, offlineQueue, pendingAckRegistry);
    }

    /** 供单测直接注入配置记录，免去构造整个 {@link AppProperties}。 */
    MqttChannelProbe(AppProperties.Mqtt mqtt,
                     PahoMqttClientManager clientManager,
                     InboundMessageQueue inboundQueue,
                     OfflineMessageQueue offlineQueue,
                     PendingAckRegistry pendingAckRegistry) {
        this.mqtt = mqtt;
        this.clientManager = clientManager;
        this.inboundQueue = inboundQueue;
        this.offlineQueue = offlineQueue;
        this.pendingAckRegistry = pendingAckRegistry;
    }

    /**
     * 计算通道当前状态。
     *
     * <p>返回 {@link Health} 是复用平台已依赖的类型（{@code Status} 语义众所周知），
     * 但本类<b>不是</b> Spring 的 {@code HealthIndicator} Bean —— 它不会被注册进健康端点。
     */
    public Health check() {
        boolean enabled = mqtt.enabled();
        MqttConnectionState state = clientManager.state();
        boolean connected = clientManager.isConnected();

        Health.Builder builder;
        String reason;
        if (!enabled) {
            builder = Health.up();
            reason = "disabled";
        } else if (connected && state == MqttConnectionState.CONNECTED) {
            builder = Health.up();
            reason = "connected";
        } else if (state == MqttConnectionState.FAILED) {
            // FAILED 是凭据换取失败等致命错误：重连不会自愈，必须人工介入，与普通断链分开
            builder = Health.down();
            reason = "credentials-or-config-failed";
        } else {
            builder = Health.down();
            reason = "channel-down";
        }

        return builder.withDetail("reason", reason)
                .withDetail("enabled", enabled)
                .withDetail("brokerUrl", mqtt.brokerUrl())
                .withDetail("state", state.name())
                .withDetail("reconnectAttempts", clientManager.reconnectAttempts())
                .withDetail("published", clientManager.publishedCount())
                .withDetail("publishFailed", clientManager.publishFailedCount())
                .withDetail("inboundQueueSize", inboundQueue.size())
                .withDetail("offlineQueueSize", offlineQueue.size())
                .withDetail("pendingAck", pendingAckRegistry.size())
                // 三类丢弃分开累加：总量用于告警，来源用于定位是谁在丢 —— 合并后无法区分
                .withDetail("droppedTotal", inboundQueue.droppedCount()
                        + offlineQueue.droppedByPressure()
                        + offlineQueue.droppedLowPriority())
                .build();
    }

    /** 通道是否处于「可用」状态（UP 语义，未启用也算可用）。 */
    public boolean isUp() {
        return Status.UP.equals(check().getStatus());
    }
}
