package com.genvict.dssad.cloud.service;

import com.genvict.dssad.cloud.common.store.StateStore;
import com.genvict.dssad.cloud.config.AppProperties;
import com.genvict.dssad.cloud.mqtt.core.OfflineMessageQueue;
import com.genvict.dssad.cloud.mqtt.core.MqttPublisher;
import com.genvict.dssad.cloud.mqtt.core.PendingAckRegistry;
import com.genvict.dssad.cloud.mqtt.topic.TopicBuilder;
import com.genvict.dssad.cloud.ratelimit.RateLimiter;
import com.genvict.dssad.cloud.support.TestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link MqttCommandService} 发布限流行为断言（缺陷 P-03 的接线验证）。
 *
 * <p>核心断言逻辑：
 * <ul>
 *   <li>文档 9.1「单车辆下行 10 条/秒」在业务发布唯一出口处生效；</li>
 *   <li><b>被拒 ≠ 丢弃</b>：报文转离线队列缓发（补传通道每秒 20 条全局受控倾倒），</li>
 *   <li>云云 Topic（无 VIN）不参与单车辆限流；</li>
 *   <li>未连接时不消耗限流令牌（令牌只在真正尝试发布时消费）。</li>
 * </ul>
 */
class MqttCommandServiceTest {

    private static final String CAR_TOPIC_TEMPLATE = "uvodp/low_speed/%s/accident_media/down";
    private static final String CLOUD_TOPIC = "uvodp/DSSAD-ENT-0001/task/up";

    private FakeStateStore stateStore;
    private FakePublisher publisher;
    private OfflineMessageQueue offlineQueue;
    private MqttAuditService auditService;
    private MqttCommandService service;
    private AppProperties properties;

    @BeforeEach
    void setUp() {
        properties = TestProperties.defaults();
        stateStore = new FakeStateStore();
        publisher = new FakePublisher();
        offlineQueue = new OfflineMessageQueue(properties);
        auditService = Mockito.mock(MqttAuditService.class);
        RateLimiter rateLimiter = new RateLimiter(stateStore, properties);
        service = new MqttCommandService(publisher, new TopicBuilder(), new PendingAckRegistry(properties),
                offlineQueue, auditService, properties, rateLimiter);
        stateStore.setClock(1_760_000_000_000L);
    }

    @Test
    @DisplayName("限流窗口内的发布不受影响（10 条/秒）")
    void publishWithinLimitSucceeds() {
        publisher.connected = true;
        for (int i = 1; i <= 10; i++) {
            assertTrue(service.publish(String.format(CAR_TOPIC_TEMPLATE, "VIN-A"), Map.of("seq", i),
                            "msg-" + i, 1, null, false),
                    "第 " + i + " 条应在限流内成功");
        }
        assertEquals(10, publisher.published.size(), "10 条都应真正发出");
        assertEquals(0, offlineQueue.size(), "不应有报文进入离线队列");
    }

    @Test
    @DisplayName("超速的第 11 条不丢弃，转离线队列缓发且审计记录 RATE_LIMITED")
    void overLimitGoesToOfflineQueueNotDropped() {
        publisher.connected = true;
        for (int i = 1; i <= 10; i++) {
            service.publish(String.format(CAR_TOPIC_TEMPLATE, "VIN-A"), Map.of("seq", i), "msg-" + i, 1, null, false);
        }
        boolean eleventh = service.publish(String.format(CAR_TOPIC_TEMPLATE, "VIN-A"),
                Map.of("seq", 11), "msg-11", 1, null, false);
        assertFalse(eleventh, "第 11 条应被限流拒绝");
        assertEquals(10, publisher.published.size(), "被拒报文不应到达底层发布器");
        assertEquals(1, offlineQueue.size(), "被拒报文应转入离线队列（缓发而非丢弃）");
        verify(auditService).recordOutbound(eq(String.format(CAR_TOPIC_TEMPLATE, "VIN-A")), anyString(),
                isNull(), eq("msg-11"), any(), eq("RATE_LIMITED"));
    }

    @Test
    @DisplayName("不同 VIN 互不影响（反向对照，防『拒绝所有』式假修好）")
    void otherVinUnaffected() {
        publisher.connected = true;
        for (int i = 1; i <= 10; i++) {
            service.publish(String.format(CAR_TOPIC_TEMPLATE, "VIN-A"), Map.of("seq", i), "a-" + i, 1, null, false);
        }
        assertTrue(service.publish(String.format(CAR_TOPIC_TEMPLATE, "VIN-B"), Map.of("vin", "B"),
                        "b-1", 1, null, false),
                "A 车被限流不得影响 B 车");
        assertEquals(11, publisher.published.size());
    }

    @Test
    @DisplayName("云云 Topic（4 段，无 VIN）不参与单车辆限流")
    void cloudCloudTopicBypassesPerVehicleLimit() {
        publisher.connected = true;
        for (int i = 1; i <= 20; i++) {
            assertTrue(service.publish(CLOUD_TOPIC, Map.of("seq", i), "c-" + i, 1, null, false),
                    "云云发布没有单车辆语义，第 " + i + " 条不应被限");
        }
        assertEquals(20, publisher.published.size());
    }

    @Test
    @DisplayName("窗口滑过后限流恢复，缓发报文由补传通道接管")
    void recoversAfterWindowSlides() {
        publisher.connected = true;
        for (int i = 1; i <= 10; i++) {
            service.publish(String.format(CAR_TOPIC_TEMPLATE, "VIN-A"), Map.of("seq", i), "a-" + i, 1, null, false);
        }
        assertFalse(service.publish(String.format(CAR_TOPIC_TEMPLATE, "VIN-A"), Map.of("seq", 11),
                "a-11", 1, null, false), "窗口内第 11 条应被拒");
        stateStore.setClock(1_760_000_000_000L + 1_100);
        assertTrue(service.publish(String.format(CAR_TOPIC_TEMPLATE, "VIN-A"), Map.of("seq", 12),
                        "a-12", 1, null, false),
                "窗口滑过后应恢复放行");
    }

    @Test
    @DisplayName("限流开关关闭时不限流")
    void disabledWhenRateLimitOff() {
        publisher.connected = true;
        AppProperties off = TestProperties.rateLimitDisabled();
        MqttCommandService offService = new MqttCommandService(publisher, new TopicBuilder(),
                new PendingAckRegistry(off), offlineQueue, auditService, off, new RateLimiter(stateStore, off));
        for (int i = 1; i <= 20; i++) {
            assertTrue(offService.publish(String.format(CAR_TOPIC_TEMPLATE, "VIN-A"), Map.of("seq", i),
                            "off-" + i, 1, null, false),
                    "开关关闭时第 " + i + " 条应放行");
        }
        assertEquals(20, publisher.published.size());
    }

    @Test
    @DisplayName("未连接路径不消耗限流令牌，恢复连接后立即可发")
    void offlinePathDoesNotConsumeTokens() {
        publisher.connected = false;
        for (int i = 1; i <= 10; i++) {
            assertFalse(service.publish(String.format(CAR_TOPIC_TEMPLATE, "VIN-A"), Map.of("seq", i),
                    "q-" + i, 1, null, false), "未连接时应进离线队列（返回 false）");
        }
        assertEquals(10, offlineQueue.size());
        verify(auditService, never()).recordOutbound(anyString(), anyString(), any(), any(), any(), eq("OK"));
        // 若未连接路径误耗令牌，此时窗口计数已达 10，恢复连接后第一条就会被拒 —— 用此差异钉死行为
        publisher.connected = true;
        assertTrue(service.publish(String.format(CAR_TOPIC_TEMPLATE, "VIN-A"), Map.of("after", true),
                        "after-reconnect", 1, null, false),
                "恢复连接后第一条必须成功（离线路径不得消耗令牌）");
    }

    /** 记录发布的假发布器。 */
    private static final class FakePublisher implements MqttPublisher {
        private final List<String> published = new ArrayList<>();
        private boolean connected;

        @Override
        public boolean publish(String topic, Object payload, int qos, boolean retained) {
            published.add(topic);
            return true;
        }

        @Override
        public boolean isConnected() {
            return connected;
        }
    }

    /**
     * 可控时钟的内存 {@link StateStore}（与 {@code SlidingWindowRateLimiterTest} 同型）：
     * 只实现限流用到的四个方法，时钟手动推进以精确控制窗口边界。
     */
    private static final class FakeStateStore implements StateStore {

        private final Map<String, Long> counters = new ConcurrentHashMap<>();
        private final AtomicLong clock = new AtomicLong();

        void setClock(long millis) {
            clock.set(millis);
        }

        @Override
        public long nowMillis() {
            return clock.get();
        }

        @Override
        public Optional<String> get(String key) {
            Long value = counters.get(key);
            return value == null ? Optional.empty() : Optional.of(value.toString());
        }

        @Override
        public long increment(String key, Duration ttl) {
            return counters.merge(key, 1L, Long::sum);
        }

        @Override
        public void put(String key, String value, Duration ttl) {
            counters.put(key, Long.parseLong(value));
        }

        @Override
        public boolean setIfAbsent(String key, String value, Duration ttl) {
            return counters.putIfAbsent(key, Long.parseLong(value)) == null;
        }

        @Override
        public void delete(String key) {
            counters.remove(key);
        }
    }
}
