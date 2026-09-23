package com.genvict.dssad.cloud.common.cache;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.lang.Nullable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * 二级缓存的 {@link CacheManager} 实现。
 *
 * <p>对比 Spring Boot 自动配置的 {@code CaffeineCacheManager}：
 * <ul>
 *   <li>自动配置只能提供 <b>单级</b> 缓存，{@code spring.cache.type} 只能选一个后端；</li>
 *   <li>自动配置的 TTL 是<b>全局统一</b>的，而本平台的统计数据「总览」需要 5 秒
 *       （大屏 10 秒刷新，要求近实时），「趋势/故障榜」可以 60 秒 ——
 *       用统一 TTL 只能迁就最短的那个，白白浪费命中率。</li>
 * </ul>
 * 因此这里自己管理：<b>每个缓存名独立配置 L1 容量/TTL 与 L2 TTL</b>，
 * 并按需创建（{@code computeIfAbsent} 保证线程安全，同一缓存名只会被构建一次）。
 *
 * <p>未在配置中声明的缓存名会按默认值创建，而不是返回 {@code null} ——
 * 后者会让 Spring 静默跳过缓存（无任何日志），是最难发现的配置疏漏。
 */
public class TwoLevelCacheManager implements CacheManager {

    private final ConcurrentMap<String, Cache> caches = new ConcurrentHashMap<>();
    private final Function<String, Cache> cacheFactory;
    private final Set<String> declaredNames;
    private final boolean distributed;

    /**
     * @param declaredNames 需要<b>预先创建</b>的缓存名（其余按需创建）
     * @param cacheFactory  按缓存名构建缓存的工厂
     * @param distributed   L2 是否启用
     */
    public TwoLevelCacheManager(Set<String> declaredNames,
                                Function<String, Cache> cacheFactory,
                                boolean distributed) {
        this.declaredNames = Set.copyOf(declaredNames);
        this.cacheFactory = cacheFactory;
        this.distributed = distributed;
        declaredNames.forEach(this::getCache);
    }

    @Override
    @Nullable
    public Cache getCache(String name) {
        return caches.computeIfAbsent(name, cacheFactory);
    }

    /**
     * 已创建的缓存名。
     *
     * <p>注意：Spring 的契约允许返回「当前已知」的名字集合，按需创建的缓存
     * 在首次被使用后才出现在这里。声明式缓存已在构造时预创建，因此监控端点
     * 在启动瞬间就能看到全部业务缓存。
     */
    @Override
    public Collection<String> getCacheNames() {
        return Set.copyOf(caches.keySet());
    }

    /** L2 是否启用（供监控端点展示缓存形态：本地单级 / 分布式二级）。 */
    public boolean isDistributed() {
        return distributed;
    }

    /** 声明式缓存名（不含运行期按需创建的）。 */
    public Set<String> declaredNames() {
        return declaredNames;
    }

    /**
     * 汇总各级缓存的运行指标，供 {@code /api/v1/monitor/cache} 暴露。
     *
     * <p>为什么缓存指标值得单独暴露：缓存故障是<b>静默</b>的 —— 命中率掉到 0
     * 时接口仍然正确，只是数据库压力上升数倍。只有把命中率做成指标，
     * 才能在大屏刷新变慢之前发现它。
     */
    public List<Map<String, Object>> stats() {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        caches.keySet().stream().sorted().forEach(name -> {
            Cache cache = caches.get(name);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            if (cache instanceof TwoLevelCache twoLevel) {
                long l1 = twoLevel.l1Hits();
                long l2 = twoLevel.l2Hits();
                long miss = twoLevel.misses();
                row.put("distributed", twoLevel.isDistributed());
                row.put("l1Hits", l1);
                row.put("l2Hits", l2);
                row.put("misses", miss);
                row.put("writes", twoLevel.writes());
                row.put("hitRate", Math.round(twoLevel.hitRate() * 10000) / 100.0);
            } else {
                row.put("distributed", false);
                row.put("type", cache.getClass().getSimpleName());
            }
            rows.add(row);
        });
        return rows;
    }
}
