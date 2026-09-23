package com.genvict.dssad.cloud.ratelimit;

import com.genvict.dssad.cloud.common.store.StateStore;
import com.genvict.dssad.cloud.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 限流器（对应文档 9.1：MQTT 单车辆 10 条/秒、HTTP 单车辆 100 次/分钟）。
 *
 * <p><b>为什么用计数而不是令牌桶/漏桶？</b>
 * <ul>
 *   <li>本场景限流键是「车辆」且车数量可预期，需求是「挡住异常车端把平台打爆」，
 *       精度要求不高；</li>
 *   <li>计数方案每次判定只需 2 次 {@code GET} + 1 次 {@code INCR}（约 1ms），
 *       而令牌桶需要 Lua 脚本或多次往返，在 1Hz × 千车的高频路径上，网络往返才是瓶颈。</li>
 * </ul>
 *
 * <h2>从固定窗口升级为滑动窗口</h2>
 * 固定窗口按 {@code now / W} 取整分桶，窗口边界处会出现「前半窗末尾 + 后半窗开头」
 * 各打满一次 → 瞬时最高可达 2 倍阈值（即文档 100 次/分钟的口径会被突破到 200 次）。
 * 本实现改为<b>双窗口加权</b>（业界称 sliding window counter）：
 *
 * <pre>
 *   bucket        = now / W                    // 当前窗口序号
 *   elapsedRatio  = (now % W) / W              // 当前窗口已流逝比例
 *   estimated     = previousCount × (1 − elapsedRatio) + currentCount
 *   allow ⟺ estimated &lt; limit
 * </pre>
 *
 * <p>直觉：上一窗口的计数随本窗口的时间推进<b>线性衰减</b>，于是「刚跨窗时」上一窗
 * 的满额计数仍全额计入，天然抑制了边界突刺；随着本窗口推进，上一窗影响(逐渐)归零。
 * 仅用 2 个键（当前桶 + 上一桶），不需要 Redis 有序集合，单次判定仍是常数级往返。
 *
 * <p><b>被拒绝的请求不计入计数</b>（先判定再自增）。这一点很关键：若拒绝也计数，
 * 在「上一窗满额」时持续的探测请求会让每个窗口都被填满，导致限流永久无法恢复，
 * 车辆被彻底封死。代价是高并发下判定与自增之间存在极小的竞态窗口，
 * 可能<b>略微超发</b>——对限流而言「少挡几个」远优于「误杀正常车」。
 *
 * <p><b>降级策略</b>：限流依赖的存储异常时<b>放行</b>（见 {@link StateStore} 实现），
 * 宁可短暂超限也不能把正常业务打死。
 */
@Slf4j
@Component
public class RateLimiter {

    private static final String SCOPE_HTTP = "http";
    private static final String SCOPE_MQTT_PUBLISH = "mqttpub";

    /**
     * 限流键的存活时长 = 窗口 × 该倍数。
     *
     * <p>必须 &gt; 1 个窗口，否则「上一窗口计数」在跨窗瞬间就已过期，
     * 滑动窗口会退化成固定窗口。
     */
    private static final int TTL_WINDOW_MULTIPLIER = 2;

    private final StateStore stateStore;
    private final AppProperties.RateLimit config;

    /**
     * 限流触发计数（按 scope 分桶），供监控端点暴露。
     *
     * <p><b>为什么用进程内 {@link LongAdder} 而不是写 Redis</b>：
     * 计数发生在<b>拒绝路径</b>上 —— 拒绝风暴（车端异常重试）恰恰是 Redis
     * 压力最大的时刻，再往拒绝路径上挂一次网络写会放大故障。
     * LongAdder 在多线程自增时分摊竞争，读侧汇总，零网络开销。
     * 代价是计数为<b>实例级</b>（重启清零、多实例各自累计）：
     * 全局视图由监控系统对各实例的指标求和，这是可观测性领域的标准分工。
     */
    private final Map<String, LongAdder> rejectedCounters = new ConcurrentHashMap<>();

    /** 最近一次触发限流的时间戳（毫秒，0 表示从未触发；监控排障时判断「还在触发吗」）。 */
    private volatile long lastRejectedAtMillis = 0L;

    public RateLimiter(StateStore stateStore, AppProperties properties) {
        this.stateStore = stateStore;
        this.config = properties.rateLimit();
    }

    /**
     * 通用限流判定（滑动窗口）。
     *
     * @param scope  限流域（http / mqttpub）
     * @param id     限流主体（车辆 VIN 或企业 ID）
     * @param limit  窗口内允许的最大次数，{@code <=0} 表示不限流
     * @param window 窗口时长
     * @return 允许通过返回 {@code true}
     */
    public boolean tryAcquire(String scope, String id, int limit, Duration window) {
        if (!config.enabled() || limit <= 0 || id == null) {
            return true;
        }

        long windowMillis = window.toMillis();
        long now = stateStore.nowMillis();
        long bucket = now / windowMillis;
        long elapsed = now % windowMillis;

        String currentKey = key(scope, id, bucket);
        String previousKey = key(scope, id, bucket - 1);

        // 上一窗口的计数按「本窗口已流逝比例」线性衰减，得到本时刻的等效窗口计数。
        // 全整数运算，避免浮点误差导致边界判定抖动。
        long previousWeighted = readCount(previousKey) * (windowMillis - elapsed) / windowMillis;
        long estimated = previousWeighted + readCount(currentKey);

        if (estimated >= limit) {
            recordRejection(scope);
            log.warn("[限流] 触发限制 scope={} id={} 当前窗口等效计数={} limit={}",
                    scope, id, estimated, limit);
            return false;
        }

        long afterIncrement = stateStore.increment(currentKey, window.multipliedBy(TTL_WINDOW_MULTIPLIER));
        if (afterIncrement == 0L) {
            // StateStore 降级（返回 0）代表计数不可用，放行
            return true;
        }
        // 读计数 → 判定 → 自增之间存在竞态，极端并发下可能自增后才越界；
        // 此时拒绝本次请求，但**不回滚计数**（回滚本身又是一次竞态）。
        if (afterIncrement > limit) {
            recordRejection(scope);
            return false;
        }
        return true;
    }

    /** 车辆 HTTP 接口限流（文档 9.1：100 次/分钟）。 */
    public boolean tryAcquireHttp(String vin) {
        return tryAcquire(SCOPE_HTTP, vin, config.httpPerMinute(), Duration.ofMinutes(1));
    }

    /** MQTT 发布限流（文档 9.1：10 条/秒）。 */
    public boolean tryAcquireMqttPublish(String vin) {
        return tryAcquire(SCOPE_MQTT_PUBLISH, vin, config.mqttPerSecond(), Duration.ofSeconds(1));
    }

    /** 供监控端点读取当前配置。 */
    public AppProperties.RateLimit config() {
        return config;
    }

    /** 本实例累计触发限流次数（指定 scope，自启动起）。 */
    public long rejectedCount(String scope) {
        LongAdder adder = rejectedCounters.get(scope);
        return adder == null ? 0L : adder.sum();
    }

    /** 本实例 HTTP 限流触发次数。 */
    public long httpRejectedCount() {
        return rejectedCount(SCOPE_HTTP);
    }

    /** 本实例 MQTT 发布限流触发次数。 */
    public long mqttPublishRejectedCount() {
        return rejectedCount(SCOPE_MQTT_PUBLISH);
    }

    /** 最近一次触发限流的时间戳（毫秒；0 表示从未触发）。 */
    public long lastRejectedAtMillis() {
        return lastRejectedAtMillis;
    }

    /** 记录一次限流触发（两个拒绝路径共用）。 */
    private void recordRejection(String scope) {
        rejectedCounters.computeIfAbsent(scope, ignored -> new LongAdder()).increment();
        lastRejectedAtMillis = System.currentTimeMillis();
    }

    /**
     * 读取计数，任何异常/脏数据都按 0 处理。
     *
     * <p>这里刻意吞掉解析异常：限流属于「保护性旁路」，不能因为一个脏 key
     * 让正常请求全部 500。
     */
    private long readCount(String key) {
        try {
            return stateStore.get(key).map(Long::parseLong).orElse(0L);
        } catch (NumberFormatException e) {
            log.warn("[限流] 计数键内容非法，按 0 处理 key={}", key);
            return 0L;
        }
    }

    /** 限流键：{@code rl:{scope}:{id}:{bucket}}（桶号即窗口序号，同一键格式兼容固定窗口）。 */
    private String key(String scope, String id, long bucket) {
        return "rl:" + scope + ':' + id + ':' + bucket;
    }
}
