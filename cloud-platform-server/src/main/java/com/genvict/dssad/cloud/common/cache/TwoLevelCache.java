package com.genvict.dssad.cloud.common.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.support.AbstractValueAdaptingCache;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.LongAdder;

/**
 * 二级缓存：<b>L1 本地 Caffeine + L2 分布式 Redis</b>。
 *
 * <h2>为什么要两级，而不是只留一个</h2>
 * <table border="1">
 *   <caption>单级缓存的两种失败模式</caption>
 *   <tr><th>方案</th><th>优点</th><th>致命缺点</th></tr>
 *   <tr>
 *     <td>只用 Caffeine</td>
 *     <td>纳秒级读取，零网络开销</td>
 *     <td>多实例部署时各实例各持一份 → 数据不一致窗口 = 各自 TTL；
 *         实例数越多越像「随机返回旧值」（本平台生产为多实例）</td>
 *   </tr>
 *   <tr>
 *     <td>只用 Redis</td>
 *     <td>多实例强一致视图</td>
 *     <td>每次读都要一次网络往返（约 0.5~2ms）。大屏 10 秒刷新时，
 *         N 个前端同时刷新就是 N 次往返，热点 key 被反复读</td>
 *   </tr>
 * </table>
 *
 * <p>两级组合后：<b>热点读（同一实例重复读同一 key）走 L1</b>，
 * 跨实例一致性由 L2 保证。读路径为
 * {@code L1 → L2 → 回填 L1}，写路径为 {@code 同时写 L1 + L2}（write-through）。
 *
 * <h2>关键设计取舍</h2>
 * <ol>
 *   <li><b>不缓存 null</b>：null 统一用「未命中」表达，避免 {@code NullValue} 装箱
 *       以及「空值缓存该不该共享」的额外语义。</li>
 *   <li><b>L2 存「类名 + 值」信封</b>（{@code {"c":"java.util.ArrayList","v":[...]}}）：
 *       Spring 的 {@link org.springframework.cache.Cache#get(Object)} 是无类型 API，
 *       若不记录原始类型，反序列化只能得到 {@code LinkedHashMap}，
 *       强转成 POJO 时会 ClassCastException。信封方案比开启
 *       {@code activateDefaultTyping} 更可控 —— 后者会给<b>所有</b>出站 JSON
 *       注入 {@code @class}，污染 REST 响应。</li>
 *   <li><b>反序列化类名走白名单</b>：Redis 中的内容若被篡改（或误写到同一 key），
 *       记录类名 + 任意类加载是典型的反序列化攻击面。只允许
 *       {@code java.util.*} / {@code java.lang.*} / 本平台包。</li>
 *   <li><b>L2 异常一律降级</b>：Redis 抖动时按「未命中」处理并回源数据库，
 *       而不是抛异常把整个请求打挂。缓存是加速器，不能成为新的故障点。</li>
 *   <li><b>{@link #clear()} 只清 L1</b>：L2 需要 {@code SCAN} 遍历，在生产 Redis 上
 *       属于代价较高的操作。本平台的缓存都是短 TTL（≤60s）的统计类数据，
 *       让 L2 自然过期即可 —— 刻意不提供「allEntries 清空」能力，
 *       以免误用成高频操作。</li>
 * </ol>
 *
 * @author 车路通 DSSAD 云平台研发组
 * @since 1.0.0
 */
@Slf4j
public class TwoLevelCache extends AbstractValueAdaptingCache {

    /** L2 信封字段：原始类名。 */
    private static final String FIELD_CLASS = "c";

    /** L2 信封字段：原始值。 */
    private static final String FIELD_VALUE = "v";

    /** 允许反序列化的包前缀白名单。 */
    private static final String[] ALLOWED_PACKAGE_PREFIXES = {
            "java.util.", "com.genvict.dssad.cloud."
    };

    /**
     * 允许反序列化的标量类型白名单。
     *
     * <p>刻意<b>不</b>放行整个 {@code java.lang.*}：那个包下有
     * {@code Runtime}、{@code ProcessBuilder}、{@code ClassLoader} 等类型，
     * 一旦 Redis 内容被写入方篡改（或与同一实例上其他业务串了 key），
     * 「记录类名 + 反射还原」就是标准的反序列化攻击面。逐个列出真正会用到的标量，
     * 白名单外一律降级为未命中（回源重算），代价只是少一次缓存命中。
     */
    private static final Set<String> ALLOWED_SCALAR_TYPES = Set.of(
            String.class.getName(),
            Integer.class.getName(),
            Long.class.getName(),
            Double.class.getName(),
            Float.class.getName(),
            Boolean.class.getName(),
            java.math.BigDecimal.class.getName()
    );

    private final String name;
    private final Cache<String, Object> l1;
    /** {@code null} 表示未启用 L2（如本地开发 / 单机部署）。 */
    private final StringRedisTemplate redis;
    private final Duration l2Ttl;
    private final ObjectMapper objectMapper;
    private final String redisKeyPrefix;

    private final LongAdder l1Hits = new LongAdder();
    private final LongAdder l2Hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder writes = new LongAdder();

    /**
     * @param name         缓存名
     * @param l1           Caffeine 一级缓存（已按容量与 TTL 构建）
     * @param redis        Redis 模板，传 {@code null} 关闭 L2
     * @param l2Ttl        L2 条目有效期
     * @param objectMapper 用于 L2 序列化（复用 Spring Boot 配置的实例，保证时间/命名策略一致）
     */
    public TwoLevelCache(String name,
                         Cache<String, Object> l1,
                         @Nullable StringRedisTemplate redis,
                         Duration l2Ttl,
                         ObjectMapper objectMapper) {
        // allowNullValues = false：null 用「未命中」表达，见类注释取舍 1
        super(false);
        this.name = name;
        this.l1 = l1;
        this.redis = redis;
        this.l2Ttl = l2Ttl;
        this.objectMapper = objectMapper;
        this.redisKeyPrefix = "dssad:cache:" + name + ':';
    }

    /** 是否启用了 L2（供监控端点展示缓存形态）。 */
    public boolean isDistributed() {
        return redis != null;
    }

    // ==================== Cache 接口实现 ====================

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getNativeCache() {
        return l1;
    }

    /**
     * 读路径：L1 → L2 →（命中 L2 时回填 L1）。
     *
     * <p>返回 {@code null} 表示未命中，由 Spring 的缓存拦截器回源方法体。
     */
    @Override
    @Nullable
    protected Object lookup(Object key) {
        String cacheKey = String.valueOf(key);

        Object localHit = l1.getIfPresent(cacheKey);
        if (localHit != null) {
            l1Hits.increment();
            return localHit;
        }

        if (redis == null) {
            misses.increment();
            return null;
        }

        String json;
        try {
            json = redis.opsForValue().get(redisKeyPrefix + cacheKey);
        } catch (RuntimeException e) {
            log.error("[缓存] L2 读取失败，降级为未命中 cache={} key={}", name, cacheKey, e);
            misses.increment();
            return null;
        }
        if (json == null || json.isBlank()) {
            misses.increment();
            return null;
        }

        Object value = decode(json, cacheKey);
        if (value == null) {
            misses.increment();
            return null;
        }
        l2Hits.increment();
        // 回填 L1：同一个实例后续 N 次读取都不再打 Redis
        l1.put(cacheKey, value);
        return value;
    }

    @Override
    public void put(Object key, @Nullable Object value) {
        if (value == null) {
            // 不缓存 null，显式 put(null) 语义等价于「清掉」
            evict(key);
            return;
        }
        String cacheKey = String.valueOf(key);
        l1.put(cacheKey, value);
        writes.increment();
        writeThrough(cacheKey, value);
    }

    @Override
    public void evict(Object key) {
        String cacheKey = String.valueOf(key);
        l1.invalidate(cacheKey);
        if (redis == null) {
            return;
        }
        try {
            redis.delete(redisKeyPrefix + cacheKey);
        } catch (RuntimeException e) {
            log.error("[缓存] L2 失效失败，将由 TTL 自然过期 cache={} key={}", name, cacheKey, e);
        }
    }

    /**
     * 只清 L1；L2 交给 TTL 自然过期。
     *
     * @see TwoLevelCache 类注释取舍 5
     */
    @Override
    public void clear() {
        l1.invalidateAll();
        if (redis != null) {
            log.info("[缓存] 已清空本地一级缓存 cache={}；Redis 二级缓存将按 TTL={}s 自然过期",
                    name, l2Ttl.toSeconds());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        Object cached = lookup(key);
        if (cached != null) {
            return (T) cached;
        }
        try {
            T loaded = valueLoader.call();
            put(key, loaded);
            return loaded;
        } catch (Exception e) {
            throw new org.springframework.cache.Cache.ValueRetrievalException(key, valueLoader, e);
        }
    }

    // ==================== L2 编解码 ====================

    /** write-through：L1 已写，再同步写一份到 L2，失败只降级不影响业务。 */
    private void writeThrough(String cacheKey, Object value) {
        if (redis == null) {
            return;
        }
        try {
            redis.opsForValue().set(redisKeyPrefix + cacheKey, encode(value), l2Ttl);
        } catch (RuntimeException e) {
            log.error("[缓存] L2 写入失败，仅一级缓存生效 cache={} key={}", name, cacheKey, e);
        }
    }

    /**
     * 编码为 {@code {"c":"<类名>","v":<值>}}。
     *
     * <p>把 {@code List}/{@code Map} 的具体实现归一化为 JDK 标准实现类名：
     * 业务代码里大量使用 {@code Map.of()}（产出不可变的
     * {@code java.util.ImmutableCollections$MapN}，Jackson 无法实例化）与
     * {@code List.of()}。若按原类名存入，L2 反序列化必然失败 —— 而失败是静默的
     * （降级为未命中），只表现为「L2 命中率永远为 0」，极难发现。
     */
    private String encode(Object value) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put(FIELD_CLASS, normalizedTypeName(value));
        node.set(FIELD_VALUE, objectMapper.valueToTree(value));
        return node.toString();
    }

    @Nullable
    private Object decode(String json, String cacheKey) {
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode valueNode = node.path(FIELD_VALUE);
            Class<?> type = resolveAllowedClass(node.path(FIELD_CLASS).asText());
            if (type == null || valueNode.isMissingNode()) {
                return null;
            }
            return objectMapper.treeToValue(valueNode, type);
        } catch (Exception e) {
            // 用 warn 而非 error：内容损坏是「已设计好如何降级」的场景
            // （如缓存值结构随版本升级变化），不是需要人工介入的故障。
            // Redis IO 层的真实故障仍以 error 上报，两者刻意区分。
            log.warn("[缓存] L2 内容无法反序列化，按未命中处理并回源 cache={} key={} 原因={}",
                    name, cacheKey, e.getMessage());
            return null;
        }
    }

    /** 归一化容器类型名，见 {@link #encode(Object)}。 */
    private String normalizedTypeName(Object value) {
        if (value instanceof List<?>) {
            return ArrayList.class.getName();
        }
        if (value instanceof Set<?>) {
            return LinkedHashSet.class.getName();
        }
        if (value instanceof Map<?, ?>) {
            return LinkedHashMap.class.getName();
        }
        return value.getClass().getName();
    }

    /** 类名白名单校验，见 {@link TwoLevelCache} 类注释取舍 3。 */
    @Nullable
    private Class<?> resolveAllowedClass(String className) {
        if (className == null || className.isBlank()) {
            return null;
        }
        if (ALLOWED_SCALAR_TYPES.contains(className)) {
            return loadClass(className);
        }
        for (String prefix : ALLOWED_PACKAGE_PREFIXES) {
            if (className.startsWith(prefix)) {
                return loadClass(className);
            }
        }
        log.warn("[缓存] L2 类名不在白名单内，拒绝反序列化 cache={} className={}", name, className);
        return null;
    }

    @Nullable
    private Class<?> loadClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            log.warn("[缓存] L2 类名不存在，按未命中处理 cache={} className={}", name, className);
            return null;
        }
    }

    // ==================== 监控指标 ====================

    /** 一级命中次数。 */
    public long l1Hits() {
        return l1Hits.sum();
    }

    /** 二级命中次数（同时反映「跨实例读到别的实例写的数据」的次数）。 */
    public long l2Hits() {
        return l2Hits.sum();
    }

    /** 未命中次数（回源数据库的次数）。 */
    public long misses() {
        return misses.sum();
    }

    /** 写入次数。 */
    public long writes() {
        return writes.sum();
    }

    /** 总体命中率（0~1）。 */
    public double hitRate() {
        long total = l1Hits() + l2Hits() + misses();
        return total == 0 ? 0.0 : (l1Hits() + l2Hits()) * 1.0 / total;
    }
}
