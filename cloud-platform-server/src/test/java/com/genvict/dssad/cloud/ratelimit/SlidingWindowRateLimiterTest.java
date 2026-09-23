package com.genvict.dssad.cloud.ratelimit;

import com.genvict.dssad.cloud.common.store.StateStore;
import com.genvict.dssad.cloud.support.TestProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 滑动窗口限流的<b>确定性</b>测试。
 *
 * <p>为什么不用 {@code Thread.sleep} 等真实时间流逝：
 * 固定窗口 → 滑动窗口的核心差异体现在「窗口边界前后几毫秒」的行为上，
 * 依赖真实时钟的测试在 CI 上必然抖动（进程被调度暂停 200ms 就会得到不同结论）。
 * 这里给 {@link StateStore} 注入一个可控时钟，
 * 于是每一次「跨窗口」都能被精确地一步跨过去。
 */
@DisplayName("RateLimiter：滑动窗口")
class SlidingWindowRateLimiterTest {

    private static final Duration MINUTE = Duration.ofSeconds(60);

    @Test
    @DisplayName("窗口末打满后，跨入新窗口不会立刻再放行一批（消除固定窗口的 2 倍突刺）")
    void noBurstAcrossWindowBoundary() {
        FakeStateStore store = new FakeStateStore();
        RateLimiter limiter = new RateLimiter(store, TestProperties.defaults());
        int limit = 100;

        // 窗口末尾（t = 59s，桶 0）打满 100 次
        store.setClock(59_000);
        for (int i = 1; i <= limit; i++) {
            assertTrue(limiter.tryAcquire("http", "VIN-A", limit, MINUTE), "第 " + i + " 次应放行");
        }
        assertFalse(limiter.tryAcquire("http", "VIN-A", limit, MINUTE), "已满额，继续请求必须被拒");

        // 恰好跨入桶 1（已流逝 0%）：上一窗口权重仍为 1.0 → 等效计数 = 100
        store.setClock(60_000);
        assertFalse(limiter.tryAcquire("http", "VIN-A", limit, MINUTE),
                "固定窗口实现在这一刻会再放行 100 次（实际速率短时翻倍到 200/分钟），滑动窗口必须拒绝");

        // 权重随已流逝时间线性衰减：1ms 后上一窗口等效计数降到 99 → 可放行 1 次
        store.setClock(60_001);
        assertTrue(limiter.tryAcquire("http", "VIN-A", limit, MINUTE), "衰减后应恢复放行");
        assertFalse(limiter.tryAcquire("http", "VIN-A", limit, MINUTE),
                "放行 1 次后等效计数重回 100，必须再次拒绝");
    }

    @Test
    @DisplayName("被拒绝的请求不计入计数：持续探测不会把窗口填满导致车辆被永久封死")
    void rejectedRequestsDoNotPoisonTheWindow() {
        FakeStateStore store = new FakeStateStore();
        RateLimiter limiter = new RateLimiter(store, TestProperties.defaults());
        int limit = 10;

        // 桶 0：打满 10 次
        store.setClock(1_000);
        for (int i = 0; i < limit; i++) {
            assertTrue(limiter.tryAcquire("http", "VIN-A", limit, MINUTE));
        }

        // 桶 1 起持续高频探测（每 500ms 一次），模拟「车端已经疯了」的极端场景。
        // 若被拒绝的请求也累加计数，每个窗口都会被探测填满 → 限流永不恢复 → 车辆被永久封死。
        Map<Long, Integer> allowedPerWindow = new java.util.HashMap<>();
        for (long t = 60_000; t < 360_000; t += 500) {
            store.setClock(t);
            if (limiter.tryAcquire("http", "VIN-A", limit, MINUTE)) {
                allowedPerWindow.merge(t / 60_000L, 1, Integer::sum);
            }
        }

        // 关键断言：桶 1~5 每个窗口都能重新放行 —— 计数随窗口推进线性衰减，
        // 限流会自然恢复。这正是「拒绝不计数」带来的性质。
        for (long bucket = 1; bucket <= 5; bucket++) {
            assertTrue(allowedPerWindow.getOrDefault(bucket, 0) > 0,
                    "桶 " + bucket + " 必须恢复放行；为 0 说明车辆已被永久封死");
        }

        // 再确认一次「沉寂一段时间后能恢复全部配额」
        store.setClock(600_000);
        int allowedInFreshWindow = 0;
        for (int i = 0; i < limit; i++) {
            store.setClock(660_000 + i * 10_000L);
            if (limiter.tryAcquire("http", "VIN-A", limit, MINUTE)) {
                allowedInFreshWindow++;
            }
        }
        assertTrue(allowedInFreshWindow > 0,
                "车辆必须能恢复（限流失效只是超限，永久封死是生产事故）");
    }

    @Test
    @DisplayName("平滑流量（1 次/秒）不会被误杀：600 次全部放行，尽管跨了 10 个窗口")
    void steadyTrafficIsNeverFalsePositive() {
        FakeStateStore store = new FakeStateStore();
        RateLimiter limiter = new RateLimiter(store, TestProperties.defaults());
        int limit = 100;

        // 1 次/秒 = 60 次/分钟 < 100 次/分钟，理论上不该被限流
        int rejected = 0;
        for (int second = 0; second < 600; second++) {
            store.setClock(second * 1_000L);
            if (!limiter.tryAcquire("http", "VIN-A", limit, MINUTE)) {
                rejected++;
            }
        }
        assertEquals(0, rejected, "稳态流量 60/分钟 低于阈值 100/分钟，滑动窗口不得误判");
    }

    @Test
    @DisplayName("上一窗口为空时，新窗口可立即用完整个配额（滑动窗口不无故收紧）")
    void emptyPreviousWindowAllowsFullQuota() {
        FakeStateStore store = new FakeStateStore();
        RateLimiter limiter = new RateLimiter(store, TestProperties.defaults());
        int limit = 20;

        // 沉默一整个窗口后突然来流量：桶 3 的上一桶（桶 2）计数为 0
        store.setClock(180_000);
        for (int i = 1; i <= limit; i++) {
            assertTrue(limiter.tryAcquire("http", "VIN-B", limit, MINUTE),
                    "上一窗口无流量时不应有任何额外削减，第 " + i + " 次应放行");
        }
        assertFalse(limiter.tryAcquire("http", "VIN-B", limit, MINUTE));
    }

    @Test
    @DisplayName("不同限流主体完全隔离")
    void subjectsAreIsolated() {
        FakeStateStore store = new FakeStateStore();
        RateLimiter limiter = new RateLimiter(store, TestProperties.defaults());
        int limit = 3;

        store.setClock(1_000);
        for (int i = 0; i < limit; i++) {
            assertTrue(limiter.tryAcquire("http", "VIN-A", limit, MINUTE));
        }
        assertFalse(limiter.tryAcquire("http", "VIN-A", limit, MINUTE));

        assertTrue(limiter.tryAcquire("http", "VIN-B", limit, MINUTE),
                "A 车被限流不得影响 B 车");
    }

    @Test
    @DisplayName("触发计数：只有被拒绝的请求计数，放行不计；不同 scope 分桶")
    void rejectionCountersOnlyCountRejections() {
        FakeStateStore store = new FakeStateStore();
        RateLimiter limiter = new RateLimiter(store, TestProperties.defaults());
        int limit = 5;

        store.setClock(1_000);
        for (int i = 0; i < limit; i++) {
            assertTrue(limiter.tryAcquire("http", "VIN-A", limit, MINUTE), "前 " + limit + " 次应放行");
        }
        assertFalse(limiter.tryAcquire("http", "VIN-A", limit, MINUTE));
        assertFalse(limiter.tryAcquire("http", "VIN-A", limit, MINUTE));
        // 另一主体正常放行 + 另一 scope 被拒：确认计数分桶正确
        assertTrue(limiter.tryAcquire("http", "VIN-B", limit, MINUTE));
        store.setClock(1_500);
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("mqttpub", "VIN-A", 2, Duration.ofSeconds(1));
        }

        assertEquals(2, limiter.httpRejectedCount(), "HTTP 只应计 2 次拒绝（放行不计）");
        assertTrue(limiter.mqttPublishRejectedCount() >= 1, "MQTT scope 计数独立分桶");
        assertTrue(limiter.lastRejectedAtMillis() > 0, "触发后必须记录最近触发时间");
    }

    @Test
    @DisplayName("未触发限流时计数保持为 0、最近触发时间为 0")
    void noRejectionLeavesCountersAtZero() {
        FakeStateStore store = new FakeStateStore();
        RateLimiter limiter = new RateLimiter(store, TestProperties.defaults());

        store.setClock(1_000);
        for (int i = 0; i < 50; i++) {
            assertTrue(limiter.tryAcquire("http", "VIN-C", 100, MINUTE));
        }

        assertEquals(0, limiter.httpRejectedCount());
        assertEquals(0, limiter.mqttPublishRejectedCount());
        assertEquals(0, limiter.lastRejectedAtMillis());
    }

    /**
     * 可控时钟的内存 {@link StateStore}。
     *
     * <p>只实现限流真正用到的 {@code get} / {@code increment} / {@code nowMillis}，
     * 其余方法留空 —— 刻意不复用 {@code InMemoryStateStore}，
     * 因为它无法注入时钟，而「精确跨窗口」正是本测试的全部意义。
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
