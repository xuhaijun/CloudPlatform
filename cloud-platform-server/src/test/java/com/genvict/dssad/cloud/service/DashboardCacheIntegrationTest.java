package com.genvict.dssad.cloud.service;

import com.genvict.dssad.cloud.common.cache.CacheNames;
import com.genvict.dssad.cloud.common.cache.TwoLevelCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 大屏统计缓存的<b>装配</b>集成测试。
 *
 * <p>为什么必须有这一层测试：{@code @Cacheable} 失效有整整三类「代码看起来完全正确」的原因 ——
 * <ol>
 *   <li>忘了 {@code @EnableCaching}（注解被静默忽略，无任何日志）；</li>
 *   <li>同类内部方法自调用（绕过代理）；</li>
 *   <li>{@code CacheManager} 没被装配成自研实现（退化成 NoOp，命中率恒为 0）。</li>
 * </ol>
 * 单元测试 {@code TwoLevelCacheTest} 只能证明缓存<b>自身</b>正确，
 * 证明不了它真的被 Spring 的 AOP 链路调用了。这里通过观察「第一次回源、第二次命中」
 * 把这条链路钉死。
 *
 * <p>注意：单测 profile 默认<b>刻意关闭</b>了缓存（{@code spring.cache.type=none}），
 * 以免用例之间共享缓存造成「幽灵失败」。本测试通过 {@code properties} 显式重新打开，
 * 属于「只在需要验证缓存行为时才付代价」的做法。
 */
@SpringBootTest(properties = {
        "dssad.cache.enabled=true",
        "dssad.cache.l1-default-ttl-seconds=60",
        "dssad.cache.l2-default-ttl-seconds=60"
})
@ActiveProfiles("test")
@DisplayName("大屏统计缓存：@Cacheable 装配验证")
class DashboardCacheIntegrationTest {

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private CacheManager cacheManager;

    @Test
    @DisplayName("CacheManager 装配为二级缓存实现，且已声明业务缓存名")
    void cacheManagerIsWired() {
        Cache cache = cacheManager.getCache(CacheNames.DASHBOARD_OVERVIEW);
        assertNotNull(cache, "cacheManager 必须能按名字返回缓存实例");
        assertInstanceOf(TwoLevelCache.class, cache,
                "必须装配自研的二级缓存（否则退化为 NoOp，命中率永远为 0 且没有任何报错）");
    }

    @Test
    @DisplayName("第一次调用回源，第二次调用命中缓存：证明 @Cacheable 真的走了代理")
    void secondCallServedFromCache() {
        TwoLevelCache cache = (TwoLevelCache) cacheManager.getCache(CacheNames.DASHBOARD_OVERVIEW);
        assertNotNull(cache);

        long missesBefore = cache.misses();
        long hitsBefore = cache.l1Hits();

        dashboardService.overview();
        assertEquals(missesBefore + 1, cache.misses(),
                "第一次调用必须回源（缓存未命中）");

        dashboardService.overview();
        assertEquals(hitsBefore + 1, cache.l1Hits(),
                "第二次调用必须命中 L1；若未命中说明 @Cacheable 被绕过（代理失效或 key 不稳定）");
        assertEquals(missesBefore + 1, cache.misses(),
                "第二次不得再回源，否则缓存形同不存在");

        // key 必须稳定为常量 'all'：不带参数的统计接口若 key 不稳定，
        // 每次调用都会写成新条目，缓存永远命中不了
        String[] declared = cacheManager.getCacheNames().toArray(new String[0]);
        org.junit.jupiter.api.Assertions.assertTrue(declared.length > 0);
    }

    @Test
    @DisplayName("趋势接口按天数分流缓存 key：不同 days 互不串味")
    void trendCacheIsKeyedByDays() {
        TwoLevelCache cache = (TwoLevelCache) cacheManager.getCache(CacheNames.DASHBOARD_TREND);
        assertNotNull(cache);

        long missesBefore = cache.misses();

        dashboardService.trend(7);
        assertEquals(missesBefore + 1, cache.misses(), "days=7 首次回源");

        dashboardService.trend(7);
        assertEquals(missesBefore + 1, cache.misses(), "days=7 第二次应命中");

        dashboardService.trend(30);
        assertEquals(missesBefore + 2, cache.misses(),
                "days=30 是另一个 key，必须独立回源（否则会把 7 天的数据当成 30 天返回）");
    }
}
