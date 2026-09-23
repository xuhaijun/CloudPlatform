package com.genvict.dssad.cloud.mqtt.core;

import com.genvict.dssad.cloud.config.AppProperties;
import com.genvict.dssad.cloud.integration.MqttCredentialProvider;
import com.genvict.dssad.cloud.mqtt.model.MqttConnectionState;
import com.genvict.dssad.cloud.mqtt.topic.TopicBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MQTT 客户端管理器（Eclipse Paho，MQTT 3.1.1）。
 *
 * <p>承担「连接生命周期」的全部职责，是文档第 5.1.1 / 5.1.3 / 8.2 节的落地实现：
 *
 * <h3>1. 连接参数（文档 5.1.1 / 6.1.1）</h3>
 * <ul>
 *   <li>{@code clientId = e_{enterpriseId}}，{@code username = enterpriseId}；</li>
 *   <li>{@code keep_alive = 60}；</li>
 *   <li>{@code clean_session = false}（持久会话）—— 这是「服务重启后仍能收到 QoS 1 离线消息」的前提；</li>
 *   <li>password 动态换取（文档 6.2.2.1.1），失败时回落到静态配置。</li>
 * </ul>
 *
 * <h3>2. 断线重连（文档 8.2）</h3>
 * 刻意<b>关闭 Paho 自带的 automaticReconnect</b>，改为自行实现指数退避（2s→4s→8s→16s→32s→上限 60s）：
 * Paho 的自动重连策略不可配置退避序列，且无法在重连成功回调中插入「订阅恢复 + 离线补传」逻辑。
 *
 * <h3>3. 回调线程保护</h3>
 * {@code messageArrived} 只做「入队」（见 {@link InboundMessageQueue}），
 * 绝不在此执行数据库或 HTTP 操作，避免阻塞心跳与 PUBACK 发送。
 */
@Slf4j
@Component
public class PahoMqttClientManager implements MqttBridge {

    /** 连接超时（秒）。 */
    private static final int CONNECTION_TIMEOUT_SECONDS = 30;

    /** 重连成功后单次离线补传的最大条数（避免瞬间打爆对端限流）。 */
    private static final int FLUSH_BATCH_ON_CONNECT = 500;

    private final AppProperties properties;
    private final TopicBuilder topicBuilder;
    private final InboundMessageQueue inboundQueue;
    private final OfflineMessageQueue offlineQueue;
    private final MqttCredentialProvider credentialProvider;

    private volatile MqttAsyncClient client;
    private volatile MqttConnectionState state = MqttConnectionState.DISCONNECTED;
    private final AtomicInteger reconnectAttempts = new AtomicInteger();
    private final AtomicLong publishedCount = new AtomicLong();
    private final AtomicLong publishFailedCount = new AtomicLong();
    private ScheduledExecutorService reconnectScheduler;

    public PahoMqttClientManager(AppProperties properties,
                                 TopicBuilder topicBuilder,
                                 InboundMessageQueue inboundQueue,
                                 OfflineMessageQueue offlineQueue,
                                 MqttCredentialProvider credentialProvider) {
        this.properties = properties;
        this.topicBuilder = topicBuilder;
        this.inboundQueue = inboundQueue;
        this.offlineQueue = offlineQueue;
        this.credentialProvider = credentialProvider;
    }

    // ==================== 生命周期 ====================

    @PostConstruct
    void start() {
        if (!properties.mqtt().enabled()) {
            log.info("[MQTT] 未启用真实 Broker 连接（dssad.mqtt.enabled=false），"
                    + "平台将以「无 Broker 模式」运行：报文可通过模拟接口注入，下发指令进入离线队列");
            state = MqttConnectionState.DISCONNECTED;
            return;
        }
        reconnectScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mqtt-reconnect");
            thread.setDaemon(true);
            return thread;
        });
        connect();
    }

    @PreDestroy
    void stop() {
        if (reconnectScheduler != null) {
            reconnectScheduler.shutdownNow();
        }
        MqttAsyncClient current = client;
        if (current != null) {
            try {
                if (current.isConnected()) {
                    current.disconnect().waitForCompletion(5_000);
                }
                current.close();
            } catch (MqttException e) {
                log.warn("[MQTT] 关闭客户端时出现异常：{}", e.getMessage());
            }
        }
        state = MqttConnectionState.DISCONNECTED;
        log.info("[MQTT] 客户端已停止");
    }

    // ==================== 连接与重连 ====================

    /** 建立连接（首次连接或重连）。 */
    private void connect() {
        AppProperties.Mqtt config = properties.mqtt();
        String clientId = properties.mqttClientId();
        state = reconnectAttempts.get() == 0 ? MqttConnectionState.CONNECTING : MqttConnectionState.RECONNECTING;
        try {
            String password = resolvePassword(clientId);
            MqttAsyncClient newClient = new MqttAsyncClient(config.brokerUrl(), clientId, new MemoryPersistence());
            newClient.setCallback(new ClientCallback());

            MqttConnectOptions options = new MqttConnectOptions();
            options.setUserName(properties.mqttUsername());
            if (password != null && !password.isBlank()) {
                options.setPassword(password.toCharArray());
            }
            options.setKeepAliveInterval(config.keepAliveSeconds());
            options.setCleanSession(config.cleanSession());
            // 关闭 Paho 内置重连：退避序列与「重连后补传」由本类控制（文档 8.2 / 8.4）
            options.setAutomaticReconnect(false);
            options.setConnectionTimeout(CONNECTION_TIMEOUT_SECONDS);

            newClient.connect(options).waitForCompletion(CONNECTION_TIMEOUT_SECONDS * 1000L);
            this.client = newClient;
            // 连接成功后的状态/订阅/补传统一在 connectComplete 回调里完成，避免竞态
        } catch (MqttException e) {
            log.error("[MQTT] 连接 Broker 失败 broker={} clientId={} 原因={}",
                    config.brokerUrl(), clientId, e.getMessage());
            state = MqttConnectionState.RECONNECTING;
            scheduleReconnect();
        } catch (RuntimeException e) {
            log.error("[MQTT] 连接过程出现非预期异常", e);
            state = MqttConnectionState.FAILED;
        }
    }

    /** 获取连接密码：优先动态换取，失败则回落静态配置。 */
    private String resolvePassword(String clientId) {
        return credentialProvider.fetchMqttPassword(clientId)
                .orElseGet(() -> {
                    String fallback = properties.mqtt().password();
                    if (fallback == null || fallback.isBlank()) {
                        log.warn("[MQTT] 未取得 MQTT 密码且无静态配置，将以匿名方式连接（生产环境不应如此）");
                    }
                    return fallback;
                });
    }

    /**
     * 安排下一次重连（指数退避）。
     *
     * <p>文档 8.2：首次重连立即，后续 2s、4s、8s、16s，最大 60s。
     * 首次重连（{@code reconnectAttempts == 0}）延迟为 0，即立即执行。
     */
    private void scheduleReconnect() {
        if (reconnectScheduler == null || reconnectScheduler.isShutdown()) {
            return;
        }
        AppProperties.Reconnect config = properties.mqtt().reconnect();
        int attempt = reconnectAttempts.getAndIncrement();
        long delay = attempt == 0 ? 0L
                : Math.min((long) (config.initialBackoffMillis() * Math.pow(config.multiplier(), attempt - 1)),
                config.maxBackoffMillis());
        log.warn("[MQTT] {}ms 后进行第 {} 次重连（最大退避 {}ms）", delay, attempt + 1, config.maxBackoffMillis());
        reconnectScheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }

    /** 连接成功后的收尾：重置退避、恢复订阅、补传离线数据。 */
    private void onConnected() {
        state = MqttConnectionState.CONNECTED;
        reconnectAttempts.set(0);
        subscribeAll();
        int flushed = flushOffline(FLUSH_BATCH_ON_CONNECT);
        log.info("[MQTT] 已连接并完成订阅，本次补传离线报文 {} 条，剩余积压 {} 条",
                flushed, offlineQueue.size());
    }

    /** 订阅下行与（可选）车云上行主题。 */
    private void subscribeAll() {
        MqttAsyncClient current = client;
        if (current == null) {
            return;
        }
        List<String> topics = topicBuilder.subscriptionTopics(
                properties.enterpriseId(), properties.mqtt().subscribeCarUplink());
        for (String topic : topics) {
            try {
                current.subscribe(topic, 1).waitForCompletion(10_000);
                log.info("[MQTT] 已订阅 {}", topic);
            } catch (MqttException e) {
                // 单个主题订阅失败不影响其他主题；Broker 权限问题是常见原因
                log.error("[MQTT] 订阅失败 topic={} 原因={}（请检查 Broker ACL 是否放通该主题）",
                        topic, e.getMessage());
            }
        }
    }

    // ==================== 发布 ====================

    @Override
    public boolean publish(String topic, Object payload, int qos, boolean retained) {
        MqttAsyncClient current = client;
        if (current == null || !current.isConnected()) {
            return false;
        }
        try {
            String json = payload instanceof String text
                    ? text : com.genvict.dssad.cloud.common.util.JsonUtils.toJson(payload);
            MqttMessage message = new MqttMessage(json.getBytes(StandardCharsets.UTF_8));
            message.setQos(qos);
            message.setRetained(retained);
            current.publish(topic, message).waitForCompletion(10_000);
            publishedCount.incrementAndGet();
            return true;
        } catch (MqttException e) {
            publishFailedCount.incrementAndGet();
            log.error("[MQTT] 发布失败 topic={} qos={} 原因={}", topic, qos, e.getMessage());
            return false;
        } catch (RuntimeException e) {
            publishFailedCount.incrementAndGet();
            log.error("[MQTT] 发布异常 topic={}", topic, e);
            return false;
        }
    }

    @Override
    public boolean isConnected() {
        MqttAsyncClient current = client;
        return state == MqttConnectionState.CONNECTED && current != null && current.isConnected();
    }

    /**
     * 补传离线报文。
     *
     * @param maxCount 单次最大条数
     * @return 实际补传条数
     */
    @Override
    public int flushOffline(int maxCount) {
        if (!isConnected()) {
            return 0;
        }
        List<OfflineMessageQueue.QueuedMessage> messages = offlineQueue.drain(maxCount);
        int success = 0;
        for (OfflineMessageQueue.QueuedMessage message : messages) {
            if (publish(message.topic(), message.payloadJson(), message.qos())) {
                success++;
            } else {
                // 补传失败：重新入队，等待下个周期（避免在循环里无限重试）
                offlineQueue.offer(message);
            }
        }
        if (success > 0) {
            log.info("[MQTT] 离线补传完成 {}/{} 条", success, messages.size());
        }
        return success;
    }

    // ==================== 监控指标 ====================

    /** 当前连接状态。 */
    public MqttConnectionState state() {
        return state;
    }

    /** 已成功发布的报文数。 */
    public long publishedCount() {
        return publishedCount.get();
    }

    /** 发布失败次数。 */
    public long publishFailedCount() {
        return publishFailedCount.get();
    }

    /** 已发生的重连尝试次数。 */
    public int reconnectAttempts() {
        return reconnectAttempts.get();
    }

    // ==================== Paho 回调 ====================

    /**
     * 客户端回调实现。
     *
     * <p>刻意做成内部类并使用外部类的字段：Paho 的回调在客户端内部线程执行，
     * 所有共享状态都通过 volatile / 原子类访问，避免额外同步开销。
     */
    private final class ClientCallback implements MqttCallbackExtended {

        @Override
        public void connectComplete(boolean reconnect, String serverUri) {
            log.info("[MQTT] {}成功 serverUri={}", reconnect ? "重连" : "连接", serverUri);
            onConnected();
        }

        @Override
        public void connectionLost(Throwable cause) {
            log.error("[MQTT] 连接断开：{}，将按指数退避重连", cause == null ? "未知原因" : cause.getMessage());
            state = MqttConnectionState.RECONNECTING;
            scheduleReconnect();
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            // 只做入队：绝不在此执行数据库/HTTP 操作（否则会阻塞心跳与 PUBACK）
            byte[] payload = message.getPayload();
            MqttEnvelope envelope = MqttEnvelope.of(topic, payload);
            if (!inboundQueue.offer(envelope)) {
                log.warn("[MQTT] 入站队列已满，已丢弃最旧报文 topic={} 累计丢弃={}",
                        topic, inboundQueue.droppedCount());
            }
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            // QoS 1/2 的投递完成回调。业务确认以对端应用层 ACK 为准（见 PendingAckRegistry），
            // 此处仅用于调试定位「本地已发出但对端未处理」的场景。
            if (log.isTraceEnabled()) {
                log.trace("[MQTT] 本地投递完成 messageId={}", token.getMessageId());
            }
        }
    }
}
