package com.genvict.dssad.cloud.common.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 二级缓存测试。
 *
 * <p>测试策略：用 Mockito 假造 Redis（{@link StringRedisTemplate}）而不是起真 Redis ——
 * 本类要验证的是<b>编解码与降级逻辑</b>（哪一级命中、类型能否还原、脏数据是否安全），
 * 这些与 Redis 本身无关，用假实现即可 100% 确定性地断言调用次数。
 */
@DisplayName("二级缓存 TwoLevelCache")
class TwoLevelCacheTest {

    private ValueOperations<String, String> redisOps;
    private StringRedisTemplate redisTemplate;

    /** 模拟 Redis 中的实际内容，让「写入 → 另一实例读取」这本链路可以在单测中复现。 */
    private final Map<String, String> fakeRedis = new java.util.HashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisOps = mock(ValueOperations.class);
        redisTemplate = mock(StringRedisTemplate.class);
        fakeRedis.clear();

        when(redisTemplate.opsForValue()).thenReturn(redisOps);
        doAnswer(inv -> {
            fakeRedis.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(redisOps).set(anyString(), anyString(), any(Duration.class));
        when(redisOps.get(anyString())).thenAnswer(inv -> fakeRedis.get(inv.getArgument(0)));
        // delete 必须真的从这个假 Redis 里移除条目 —— 否则 evict 后仍会从 L2 读到旧值，
        // 让「put(null) 等价于清除」这类断言产生假失败（生产用真 Redis 不存在此问题）
        when(redisTemplate.delete(anyString())).thenAnswer(inv -> fakeRedis.remove(inv.getArgument(0)) != null);
    }

    /** 构建缓存；{@code withRedis=false} 模拟单机部署（无 L2）。 */
    private TwoLevelCache newCache(boolean withRedis) {
        Cache<String, Object> l1 = Caffeine.newBuilder()
                .maximumSize(1_000)
                .expireAfterWrite(Duration.ofMinutes(5))
                .build();
        return new TwoLevelCache("test", l1, withRedis ? redisTemplate : null,
                Duration.ofMinutes(10), new ObjectMapper());
    }

    @Nested
    @DisplayName("读路径")
    class ReadPath {

        @Test
        @DisplayName("一次写入后重复读取只命中 L1，不再打 Redis")
        void l1HitSkipsRedis() {
            TwoLevelCache cache = newCache(true);
            cache.put("k", Map.of("a", 1));

            for (int i = 0; i < 20; i++) {
                assertEquals(Map.of("a", 1), cache.get("k").get());
            }

            // 关键断言：L2 只被写、从未被读 —— 这正是 L1 存在的意义（省掉网络往返）
            verify(redisOps, times(1)).set(anyString(), anyString(), any(Duration.class));
            verify(redisOps, never()).get(anyString());
            assertEquals(0, cache.l2Hits());
            assertEquals(20, cache.l1Hits());
        }

        @Test
        @DisplayName("L1 未命中时回落 L2，并把结果回填 L1")
        void l2HitBackfillsL1() {
            // 实例 A 写（模拟另一个 Redis 客户端 / 另一个应用实例写入了共享 L2）
            newCache(true).put("k", List.of("x", "y"));

            // 实例 B：全新的空 L1，但共享同一个 Redis
            TwoLevelCache instanceB = newCache(true);
            assertEquals(List.of("x", "y"), instanceB.get("k").get());

            assertEquals(1, instanceB.l2Hits(), "第一次读应落到 L2");
            assertEquals(0, instanceB.misses(), "L2 命中不算穿透");

            // 回填生效：第二次读直接走 L1
            assertEquals(List.of("x", "y"), instanceB.get("k").get());
            assertEquals(1, instanceB.l1Hits());
            verify(redisOps, times(1)).get(anyString());
        }

        @Test
        @DisplayName("L1/L2 都未命中才是穿透，命中率统计据此计算")
        void missIsCounted() {
            TwoLevelCache cache = newCache(true);
            assertNull(cache.get("absent"));
            assertEquals(1, cache.misses());
            assertEquals(0.0, cache.hitRate());

            cache.put("k", "v");
            cache.get("k");
            assertTrue(cache.hitRate() > 0.0);
        }
    }

    @Nested
    @DisplayName("L2 编解码")
    class Encoding {

        @Test
        @DisplayName("容器类型归一化：List.of / Map.of 等不可变实现不能被 Jackson 实例化，必须还原成标准实现")
        void normalizesImmutableCollectionTypes() {
            TwoLevelCache cache = newCache(true);
            // 业务代码里 faultCategoryDistribution / topFaultVehicles 用的就是 List.of + Map.of
            cache.put("k", List.of(Map.of("a", 1)));

            String stored = fakeRedis.values().iterator().next();
            assertTrue(stored.contains("\"c\":\"java.util.ArrayList\""),
                    "类名必须归一化为 java.util.ArrayList，否则 L2 反序列化静默失败（命中率永远为 0）：" + stored);

            // 另一个实例从 L2 还原
            Object restored = newCache(true).get("k").get();
            assertInstanceOf(List.class, restored);
            Object first = ((List<?>) restored).get(0);
            assertInstanceOf(Map.class, first);
            assertEquals(1, ((Map<?, ?>) first).get("a"));
        }

        @Test
        @DisplayName("L2 内容为白名单外的类名时降级为未命中，绝不反射实例化")
        void rejectsClassOutsideWhitelist() {
            fakeRedis.put("dssad:cache:test:k", "{\"c\":\"java.lang.Runtime\",\"v\":{}}");
            TwoLevelCache cache = newCache(true);

            assertNull(cache.get("k"), "白名单外的类名必须按未命中处理（回源重算），而不是抛异常");
            assertEquals(1, cache.misses());
        }

        @Test
        @DisplayName("L2 内容损坏（非法 JSON / 缺字段）时降级为未命中")
        void toleratesCorruptedPayload() {
            TwoLevelCache cache = newCache(true);

            fakeRedis.put("dssad:cache:test:badJson", "{not-json");
            assertNull(cache.get("badJson"));

            fakeRedis.put("dssad:cache:test:noType", "{\"v\":[1,2]}");
            assertNull(cache.get("noType"));

            fakeRedis.put("dssad:cache:test:noValue", "{\"c\":\"java.util.ArrayList\"}");
            assertNull(cache.get("noValue"));
        }
    }

    @Nested
    @DisplayName("降级与语义")
    class Degradation {

        @Test
        @DisplayName("Redis 抛异常时按未命中处理并回源，不把请求打挂")
        void degradesWhenRedisFails() {
            when(redisOps.get(anyString())).thenThrow(new RuntimeException("redis down"));
            when(redisTemplate.delete(anyString())).thenThrow(new RuntimeException("redis down"));
            TwoLevelCache cache = newCache(true);

            assertNull(cache.get("k"));
            assertEquals("fallback", cache.get("k", () -> "fallback"),
                    "L2 故障必须回源，缓存是加速器不能变成新的故障点");
            cache.evict("k");
        }

        @Test
        @DisplayName("不缓存 null：put(null) 等价于清除，且不写 L2")
        void nullValuesAreNotCached() {
            TwoLevelCache cache = newCache(true);
            cache.put("k", "v");
            assertEquals("v", cache.get("k").get());

            cache.put("k", null);
            assertNull(cache.get("k"));
            verify(redisTemplate, times(1)).delete(anyString());

            // 从未写入过的 key 传 null：不应产生任何 L2 写入
            resetL2Writes();
            cache.put("never", null);
            assertTrue(fakeRedis.keySet().stream().noneMatch(k -> k.endsWith(":never")));
        }

        @Test
        @DisplayName("单机部署（无 Redis）时退化为纯 L1，读写都不报错")
        void worksWithoutRedis() {
            TwoLevelCache cache = newCache(false);
            cache.put("k", "v");
            assertEquals("v", cache.get("k").get());
            assertEquals(0, cache.l2Hits());
            assertEquals(1, cache.l1Hits());

            cache.evict("k");
            assertNull(cache.get("k"));

            // 不缓存 null 的语义在单机模式下同样成立
            cache.put("n", null);
            assertNull(cache.get("n"));
        }

        @Test
        @DisplayName("clear() 只清 L1：L2 仍可读到旧值并回填 L1（避免误伤共享 Redis 上其他实例）")
        void clearOnlyTouchesL1() {
            TwoLevelCache cache = newCache(true);
            cache.put("k", "v");
            cache.clear();

            // 这是刻意的语义：清空动作不遍历 Redis（SCAN 在生产上是高代价操作），
            // L2 交给 TTL 自然过期；因此 clear 之后仍可能从 L2 命中并回填 L1。
            assertEquals("v", cache.get("k").get(), "L2 条目仍在，可被读回");
            assertEquals(1, cache.l2Hits());
            assertNotNull(fakeRedis.get("dssad:cache:test:k"),
                    "L2 条目必须保留，否则会误删其他实例写入的数据");
        }

        private void resetL2Writes() {
            fakeRedis.clear();
        }
    }
}
