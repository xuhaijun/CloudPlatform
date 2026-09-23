package com.genvict.dssad.cloud.mqtt.core;

import com.genvict.dssad.cloud.common.store.InMemoryStateStore;
import com.genvict.dssad.cloud.common.util.TimeUtils;
import com.genvict.dssad.cloud.mqtt.model.UploadPriority;
import com.genvict.dssad.cloud.support.TestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MQTT 可靠性机制测试：应用层 ACK 重发登记表 + 离线优先级队列 + 内存状态存储。
 *
 * <p>这组机制是「MQTT QoS 之上的可靠性补丁」—— QoS 1 只保证报文到达 Broker，
 * 不保证对端业务处理成功。文档 5.1.4.1 / 8.1 要求对端未确认或明确报错时必须重发，
 * 因此这里的用例直接对应监管考核项：<b>重发次数、重发间隔、放弃条件</b>。
 */
@DisplayName("MQTT 可靠性机制")
class MqttReliabilityTest {

    // ==================== 应用层 ACK 重发登记表 ====================

    @Nested
    @DisplayName("待确认登记表（PendingAckRegistry）")
    class PendingAck {

        private PendingAckRegistry registry;

        @BeforeEach
        void setUp() {
            // 夹具配置：未确认 5s/最多 3 次；异常 3s/最多 5 次
            registry = new PendingAckRegistry(TestProperties.defaults());
        }

        @Test
        @DisplayName("登记后可查询到，收到 res=1 后移除并返回快照")
        void registerThenComplete() {
            registry.register("msg-1", "uvodp/ent/task/up", "{\"a\":1}", 1, UploadPriority.EVENT);

            assertEquals(1, registry.size());
            assertTrue(registry.contains("msg-1"));

            PendingAckRegistry.Pending completed = registry.complete("msg-1");
            assertNotNull(completed);
            assertEquals("msg-1", completed.msgId());
            assertEquals("uvodp/ent/task/up", completed.topic());
            assertEquals(0, completed.attempts());
            assertFalse(registry.contains("msg-1"), "确认后必须从登记表移除，否则会被反复重发");
        }

        @Test
        @DisplayName("重复确认（对端重发 ACK）返回 null 而不是抛异常")
        void duplicateCompleteIsSafe() {
            registry.register("msg-1", "topic", "{}", 1, UploadPriority.EVENT);
            assertNotNull(registry.complete("msg-1"));
            assertNull(registry.complete("msg-1"), "第二次确认应返回 null");
            assertNull(registry.complete("never-registered"), "未知 msgId 应返回 null");
            assertNull(registry.complete(null), "null 入参应安全返回 null");
        }

        @Test
        @DisplayName("msgId 为空时静默跳过登记（无法配对的报文重发没有意义）")
        void blankMsgIdIsIgnored() {
            registry.register(null, "topic", "{}", 1, UploadPriority.EVENT);
            registry.register("   ", "topic", "{}", 1, UploadPriority.EVENT);
            assertEquals(0, registry.size());
        }

        @Test
        @DisplayName("未到期不重发，到期后出现在待重发清单")
        void dueRespectsSchedule() {
            registry.register("msg-1", "topic", "{}", 1, UploadPriority.EVENT);
            long now = TimeUtils.nowMillis();

            assertTrue(registry.due(now).isEmpty(), "刚发出时不应立刻重发");
            // 未确认重发间隔为 5s（TestProperties 中 confirmIntervalMillis=5000）
            assertTrue(registry.due(now + 4_000).isEmpty(), "未到 5s 不应重发");
            assertEquals(1, registry.due(now + 5_100).size(), "超过 5s 后应进入重发清单");
        }

        @Test
        @DisplayName("重发达到上限（3 次）后主动放弃并移除，避免无限重发")
        void advanceGivesUpAfterMaxAttempts() {
            registry.register("msg-1", "topic", "{}", 1, UploadPriority.EVENT);
            PendingAckRegistry.Pending pending = registry.due(TimeUtils.nowMillis() + 5_100).get(0);

            // 前 3 次推进成功，attempts 依次为 1、2、3
            for (int round = 1; round <= 3; round++) {
                assertTrue(registry.advance(pending), "第 " + round + " 次重发应被允许");
                pending = registry.due(TimeUtils.nowMillis() + 60_000).get(0);
                assertEquals(round, pending.attempts());
            }

            // 第 4 次推进时 attempts+1=4 > 3，放弃
            assertFalse(registry.advance(pending), "超过上限必须放弃");
            assertEquals(0, registry.size(), "放弃后应清理登记项，防止内存泄漏");
        }

        @Test
        @DisplayName("对端回复 res=2/3 时切换为异常重发策略：间隔 3s、上限 5 次")
        void markInvalidSwitchesStrategy() {
            registry.register("msg-1", "topic", "{}", 1, UploadPriority.EVENT);

            PendingAckRegistry.Pending switched = registry.markInvalid("msg-1");
            assertNotNull(switched);
            assertEquals(PendingAckRegistry.RetryKind.INVALID, switched.kind());
            assertEquals(1, switched.attempts(), "异常回复本身算一次失败尝试");

            // 切换后间隔应变短（3s 而非 5s），让异常报文更快重试
            assertEquals(3_000L, registry.intervalMillis(PendingAckRegistry.RetryKind.INVALID));
            assertEquals(5_000L, registry.intervalMillis(PendingAckRegistry.RetryKind.CONFIRM));
            assertEquals(5, registry.maxAttempts(PendingAckRegistry.RetryKind.INVALID));
            assertEquals(3, registry.maxAttempts(PendingAckRegistry.RetryKind.CONFIRM));

            long now = TimeUtils.nowMillis();
            assertTrue(registry.due(now + 2_000).isEmpty(), "切换后 2s 内不应重发");
            assertFalse(registry.due(now + 3_100).isEmpty(), "切换后 3s 应进入重发清单");
        }

        @Test
        @DisplayName("markInvalid 对未知 msgId 返回 null（不创建幽灵登记项）")
        void markInvalidUnknownIsSafe() {
            assertNull(registry.markInvalid("never-registered"));
            assertNull(registry.markInvalid(null));
            assertEquals(0, registry.size());
        }

        @Test
        @DisplayName("remove 返回 Optional，clear 用于测试重置")
        void removeAndClear() {
            registry.register("msg-1", "topic", "{}", 1, UploadPriority.EVENT);
            assertEquals(Optional.of("msg-1"), registry.remove("msg-1").map(
                    PendingAckRegistry.Pending::msgId));
            assertTrue(registry.remove("msg-1").isEmpty());

            registry.register("msg-2", "topic", "{}", 1, UploadPriority.EVENT);
            registry.clear();
            assertEquals(0, registry.size());
        }
    }

    // ==================== 离线优先级队列 ====================

    @Nested
    @DisplayName("离线补传队列（OfflineMessageQueue）")
    class OfflineQueue {

        @Test
        @DisplayName("出队顺序为事件 > 基础 > 状态（断线恢复后优先补传高价值数据）")
        void drainOrderFollowsPriority() {
            OfflineMessageQueue queue = new OfflineMessageQueue(TestProperties.defaults());
            enqueue(queue, "state", UploadPriority.STATE);
            enqueue(queue, "base", UploadPriority.BASE);
            enqueue(queue, "event", UploadPriority.EVENT);

            List<OfflineMessageQueue.QueuedMessage> drained = queue.drain(10);

            assertEquals(3, drained.size());
            assertEquals(UploadPriority.EVENT, drained.get(0).priority());
            assertEquals(UploadPriority.BASE, drained.get(1).priority());
            assertEquals(UploadPriority.STATE, drained.get(2).priority());
            assertEquals(0, queue.size(), "drain 后队列应为空");
        }

        @Test
        @DisplayName("容量满且新消息更重要时，淘汰低优先级数据以收下事件数据")
        void higherPriorityEvictsLowerPriority() {
            // 容量下限为 100，故此处实际容量 = 100
            OfflineMessageQueue queue = new OfflineMessageQueue(TestProperties.withOfflineCacheSize(100));
            for (int i = 0; i < 100; i++) {
                queue.offer(newMessage("state-" + i, UploadPriority.STATE));
            }
            assertEquals(100, queue.size());

            // 第 101 条是事件级：必须挤掉一条状态数据，而不是被拒收
            queue.offer(newMessage("event-1", UploadPriority.EVENT));

            assertEquals(100, queue.size(), "队列长度不应超过容量");
            assertTrue(queue.droppedByPressure() > 0, "应记录淘汰了已有低优先级消息");
            assertEquals(0, queue.droppedLowPriority(), "事件数据不应被拒收");

            List<OfflineMessageQueue.QueuedMessage> remaining = queue.drain(200);
            assertEquals(100, remaining.size());
            assertEquals(UploadPriority.EVENT, remaining.get(0).priority(),
                    "事件数据必须排在最前，优先补传");
            assertEquals("uvodp/ent/event-1/up", remaining.get(0).topic());
        }

        @Test
        @DisplayName("容量满且新消息更不重要时，拒收新消息以保护队列中的事件数据")
        void lowerPriorityIsRejectedWhenFull() {
            OfflineMessageQueue queue = new OfflineMessageQueue(TestProperties.withOfflineCacheSize(100));
            for (int i = 0; i < 100; i++) {
                queue.offer(newMessage("event-" + i, UploadPriority.EVENT));
            }

            // 队列里全是事件数据，再来 1Hz 状态数据必须被丢弃
            boolean dropped = queue.offer(newMessage("state-1", UploadPriority.STATE));

            assertTrue(dropped, "拒收新消息时应返回 true");
            assertEquals(100, queue.size());
            assertTrue(queue.droppedLowPriority() > 0, "应记录因优先级不足被拒收的消息数");
            assertEquals(0, queue.droppedByPressure(), "不应淘汰已有的高优先级数据");

            List<OfflineMessageQueue.QueuedMessage> remaining = queue.drain(200);
            assertTrue(remaining.stream().allMatch(m -> m.priority() == UploadPriority.EVENT),
                    "队列内应全为事件数据，一条状态数据都不能挤进来");
        }

        @Test
        @DisplayName("同优先级满载时表现为 FIFO 轮转（保留最新状态，淘汰最旧）")
        void samePriorityRotatesFifo() {
            OfflineMessageQueue queue = new OfflineMessageQueue(TestProperties.withOfflineCacheSize(100));
            for (int i = 0; i < 100; i++) {
                queue.offer(newMessage("state-" + i, UploadPriority.STATE));
            }
            queue.offer(newMessage("state-newest", UploadPriority.STATE));

            List<OfflineMessageQueue.QueuedMessage> remaining = queue.drain(200);
            assertEquals(100, remaining.size());
            assertTrue(remaining.stream().noneMatch(m -> m.topic().equals("uvodp/ent/state-0/up")),
                    "最旧的一条应被淘汰");
            assertTrue(remaining.stream().anyMatch(m -> m.topic().equals("uvodp/ent/state-newest/up")),
                    "最新的状态应被保留（长时间断线后需要最新位置）");
        }

        @Test
        @DisplayName("空队列与 clear 行为安全")
        void clearIsSafe() {
            OfflineMessageQueue queue = new OfflineMessageQueue(TestProperties.withOfflineCacheSize(100));
            assertTrue(queue.drain(5).isEmpty(), "空队列 drain 应返回空列表");
            queue.offer(null);
            assertEquals(0, queue.size(), "null 入队应被忽略");
            queue.offer(newMessage("state-1", UploadPriority.STATE));
            queue.clear();
            assertEquals(0, queue.size());
        }

        private OfflineMessageQueue.QueuedMessage newMessage(String tag, UploadPriority priority) {
            return new OfflineMessageQueue.QueuedMessage(
                    "uvodp/ent/" + tag + "/up", "{\"tag\":\"" + tag + "\"}", 1, priority,
                    nextQueuedAt());
        }

        /**
         * 入队时间夹具。
         *
         * <p>用「基准时刻 + 单调递增序号」而不是每条都取 {@code TimeUtils.nowMillis()}：
         * 紧循环里毫秒分辨率会让上百条消息拿到<b>同一个</b> queuedAt，
         * 时间相同时 {@code PriorityQueue} 对同优先级元素的内部顺序不确定，
         * 「最旧一条被淘汰」这类断言会随机失败。这里人为保证严格递增，让 FIFO 语义可稳定验证。
         */
        private long nextQueuedAt() {
            return BASE_QUEUED_AT + queuedSeq.incrementAndGet();
        }

        private void enqueue(OfflineMessageQueue queue, String tag, UploadPriority priority) {
            queue.offer(newMessage(tag, priority));
        }

        /** 批次基准时刻，与真实时钟无关，只需保证批次内严格递增。 */
        private static final long BASE_QUEUED_AT = 1_758_506_400_000L;

        private final java.util.concurrent.atomic.AtomicLong queuedSeq =
                new java.util.concurrent.atomic.AtomicLong();
    }

    // ==================== 状态存储 ====================

    @Nested
    @DisplayName("状态存储（InMemoryStateStore）")
    class StateStoreBasics {

        private final InMemoryStateStore store = new InMemoryStateStore();

        @Test
        @DisplayName("setIfAbsent 首次成功、再次失败（去重与防重放的基础语义）")
        void setIfAbsentSemantics() {
            assertTrue(store.setIfAbsent("key", "1", java.time.Duration.ofMinutes(1)));
            assertFalse(store.setIfAbsent("key", "2", java.time.Duration.ofMinutes(1)),
                    "第二次必须失败，否则去重失效");
            assertEquals("1", store.get("key").orElseThrow(), "不应被第二次写入覆盖");
        }

        @Test
        @DisplayName("increment 从 1 开始递增，供固定窗口限流计数")
        void incrementCounts() {
            assertEquals(1L, store.increment("counter", java.time.Duration.ofMinutes(1)));
            assertEquals(2L, store.increment("counter", java.time.Duration.ofMinutes(1)));
            assertEquals(3L, store.increment("counter", java.time.Duration.ofMinutes(1)));
        }

        @Test
        @DisplayName("put/get/delete 基本读写")
        void basicReadWrite() {
            store.put("k", "v", java.time.Duration.ofMinutes(1));
            assertEquals("v", store.get("k").orElseThrow());
            store.delete("k");
            assertTrue(store.get("k").isEmpty());
        }

        @Test
        @DisplayName("已过期的键读不到（令牌过期、Nonce 窗口结束都依赖此语义）")
        void expiredEntryIsGone() throws InterruptedException {
            store.put("k", "v", java.time.Duration.ofMillis(30));
            assertEquals("v", store.get("k").orElseThrow());
            Thread.sleep(60);
            assertTrue(store.get("k").isEmpty(), "过期后必须读不到");
        }
    }
}
