package com.genvict.dssad.cloud.mqtt.core;

import com.genvict.dssad.cloud.config.AppProperties;
import com.genvict.dssad.cloud.mqtt.model.MqttConnectionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MQTT 通道探针测试。
 *
 * <p>测试策略：用 Mockito 假造客户端管理器与三个队列，直接构造配置记录。
 * 本类要验证的是<b>判定口径</b>（什么状态算 UP/DOWN、明细是否齐全），与真实 Broker 无关 ——
 * 起真 Broker 反而会让「断链」这条用例无法稳定复现。
 *
 * <p>这里守护的核心风险是文档 E-01/O-01：「探活全绿但一条数据都收不到」。
 * 因此每个连接状态都必须有一条用例把预期钉死，防止以后有人为了「让探活好看」把 DOWN 改成 UP。
 */
@DisplayName("MQTT 通道探针")
class MqttChannelProbeTest {

    private PahoMqttClientManager clientManager;
    private InboundMessageQueue inboundQueue;
    private OfflineMessageQueue offlineQueue;
    private PendingAckRegistry pendingAckRegistry;

    @BeforeEach
    void setUp() {
        clientManager = mock(PahoMqttClientManager.class);
        inboundQueue = mock(InboundMessageQueue.class);
        offlineQueue = mock(OfflineMessageQueue.class);
        pendingAckRegistry = mock(PendingAckRegistry.class);
    }

    /** 造一个真实的配置记录（不 mock 记录，避免依赖 Mockito 对 final 类型的处理）。 */
    private static AppProperties.Mqtt mqttConfig(boolean enabled) {
        return new AppProperties.Mqtt(enabled, "tcp://127.0.0.1:1883", "e_", "", "",
                60, false, true,
                new AppProperties.Reconnect(2000L, 60000L, 2.0),
                new AppProperties.Retry(5000L, 3, 3000L, 5),
                10000, 4);
    }

    private Health check(boolean enabled) {
        return new MqttChannelProbe(mqttConfig(enabled), clientManager,
                inboundQueue, offlineQueue, pendingAckRegistry).check();
    }

    @Nested
    @DisplayName("状态判定")
    class StatusJudgement {

        @Test
        @DisplayName("未启用 MQTT → UP（未启用不是故障，本地与单测即此态）")
        void disabledIsUp() {
            when(clientManager.state()).thenReturn(MqttConnectionState.DISCONNECTED);
            when(clientManager.isConnected()).thenReturn(false);

            Health health = check(false);

            assertEquals(Status.UP, health.getStatus());
            assertEquals("disabled", health.getDetails().get("reason"));
            assertEquals(false, health.getDetails().get("enabled"));
        }

        @Test
        @DisplayName("CONNECTED → UP，且明细含 brokerUrl 与重连次数")
        void connectedIsUp() {
            when(clientManager.state()).thenReturn(MqttConnectionState.CONNECTED);
            when(clientManager.isConnected()).thenReturn(true);
            when(clientManager.reconnectAttempts()).thenReturn(0);

            Health health = check(true);

            assertEquals(Status.UP, health.getStatus());
            assertEquals("connected", health.getDetails().get("reason"));
            assertEquals("CONNECTED", health.getDetails().get("state"));
            assertEquals("tcp://127.0.0.1:1883", health.getDetails().get("brokerUrl"));
            assertEquals(0, health.getDetails().get("reconnectAttempts"));
        }

        @Test
        @DisplayName("RECONNECTING → DOWN（瞬时重连也如实报 DOWN，持续时长交给告警规则的 for）")
        void reconnectingIsDown() {
            when(clientManager.state()).thenReturn(MqttConnectionState.RECONNECTING);
            when(clientManager.isConnected()).thenReturn(false);
            when(clientManager.reconnectAttempts()).thenReturn(7);

            Health health = check(true);

            assertEquals(Status.DOWN, health.getStatus());
            assertEquals("channel-down", health.getDetails().get("reason"));
            assertEquals("RECONNECTING", health.getDetails().get("state"));
            assertEquals(7, health.getDetails().get("reconnectAttempts"));
        }

        @Test
        @DisplayName("FAILED → DOWN，reason 区分「凭据/配置致命错误」以指向不同处置剧本")
        void failedIsDownWithDistinctReason() {
            when(clientManager.state()).thenReturn(MqttConnectionState.FAILED);
            when(clientManager.isConnected()).thenReturn(false);

            Health health = check(true);

            assertEquals(Status.DOWN, health.getStatus());
            assertEquals("credentials-or-config-failed", health.getDetails().get("reason"));
        }

        @Test
        @DisplayName("CONNECTING / DISCONNECTED → DOWN")
        void transientStatesAreDown() {
            for (MqttConnectionState state : new MqttConnectionState[]{
                    MqttConnectionState.CONNECTING, MqttConnectionState.DISCONNECTED}) {
                when(clientManager.state()).thenReturn(state);
                when(clientManager.isConnected()).thenReturn(false);

                Health health = check(true);

                assertEquals(Status.DOWN, health.getStatus(), "状态 " + state + " 应为 DOWN");
                assertEquals("channel-down", health.getDetails().get("reason"));
            }
        }

        @Test
        @DisplayName("isUp() 与 check() 结论一致（供调用方做布尔判定）")
        void isUpMatchesStatus() {
            when(clientManager.state()).thenReturn(MqttConnectionState.CONNECTED);
            when(clientManager.isConnected()).thenReturn(true);
            MqttChannelProbe probe = new MqttChannelProbe(mqttConfig(true), clientManager,
                    inboundQueue, offlineQueue, pendingAckRegistry);
            assertTrue(probe.isUp());

            when(clientManager.state()).thenReturn(MqttConnectionState.RECONNECTING);
            when(clientManager.isConnected()).thenReturn(false);
            assertFalse(probe.isUp());
        }
    }

    @Nested
    @DisplayName("明细")
    class Details {

        @Test
        @DisplayName("droppedTotal 汇总三类丢弃，便于区分「入站积压溢出」与「离线队列按优先级丢弃」")
        void droppedTotalAggregatesAllSources() {
            when(clientManager.state()).thenReturn(MqttConnectionState.CONNECTED);
            when(clientManager.isConnected()).thenReturn(true);
            when(inboundQueue.droppedCount()).thenReturn(3L);
            when(offlineQueue.droppedByPressure()).thenReturn(5L);
            when(offlineQueue.droppedLowPriority()).thenReturn(11L);

            Health health = check(true);

            assertEquals(19L, health.getDetails().get("droppedTotal"));
            assertTrue(health.getDetails().containsKey("pendingAck"));
        }

        @Test
        @DisplayName("队列积压只体现在明细里，不改变 UP/DOWN（积压时数据仍在入库，摘实例只会更糟）")
        void backlogDoesNotChangeStatus() {
            when(clientManager.state()).thenReturn(MqttConnectionState.CONNECTED);
            when(clientManager.isConnected()).thenReturn(true);
            when(inboundQueue.size()).thenReturn(6000);
            when(offlineQueue.size()).thenReturn(100);

            Health health = check(true);

            assertEquals(Status.UP, health.getStatus());
            assertEquals(6000, health.getDetails().get("inboundQueueSize"));
            assertEquals(100, health.getDetails().get("offlineQueueSize"));
        }
    }
}
