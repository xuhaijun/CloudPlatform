package com.genvict.dssad.cloud.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Map;

/**
 * 二级缓存配置（{@code dssad.cache.*}）。
 *
 * <p>刻意<b>不使用</b> {@code spring.cache.caffeine.spec} 那种「一条字符串管全部」的形式：
 * 本平台不同缓存的时效要求差一个数量级 ——
 * 大屏总览要近实时（5s），趋势/故障榜可容忍 1 分钟（60s）。
 * 统一 TTL 只能迁就最短的那个，等于白白丢掉命中率。
 *
 * <pre>
 * dssad:
 *   cache:
 *     enabled: true            # 关掉后走 Spring 默认（单测用 spring.cache.type=none 彻底关闭）
 *     l1-max-size: 20000       # 本地 Caffeine 每个缓存名的条目上限
 *     l1-default-ttl-seconds: 30
 *     l2-default-ttl-seconds: 60
 *     l1-ttl-seconds:
 *       dashboardOverview: 5
 *     l2-ttl-seconds:
 *       dashboardOverview: 5
 * </pre>
 *
 * @param enabled            是否启用二级缓存管理
 * @param l1MaxSize          L1 每个缓存名的最大条目数
 * @param l1DefaultTtlSeconds L1 默认过期秒数（未在 {@code l1TtlSeconds} 中声明的缓存名使用）
 * @param l2DefaultTtlSeconds L2 默认过期秒数
 * @param l1TtlSeconds       按缓存名覆盖 L1 过期秒数
 * @param l2TtlSeconds       按缓存名覆盖 L2 过期秒数
 */
@ConfigurationProperties(prefix = "dssad.cache")
public record CacheProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("20000") long l1MaxSize,
        @DefaultValue("30") long l1DefaultTtlSeconds,
        @DefaultValue("60") long l2DefaultTtlSeconds,
        Map<String, Long> l1TtlSeconds,
        Map<String, Long> l2TtlSeconds) {

    public CacheProperties {
        // YAML 中没有该节点时绑定结果是 null，规范化为空 Map，避免调用方到处判空
        l1TtlSeconds = l1TtlSeconds == null ? Map.of() : Map.copyOf(l1TtlSeconds);
        l2TtlSeconds = l2TtlSeconds == null ? Map.of() : Map.copyOf(l2TtlSeconds);
    }

    /** 指定缓存名的 L1 过期时长。 */
    public Duration l1Ttl(String cacheName) {
        return Duration.ofSeconds(l1TtlSeconds.getOrDefault(cacheName, l1DefaultTtlSeconds));
    }

    /** 指定缓存名的 L2 过期时长。 */
    public Duration l2Ttl(String cacheName) {
        return Duration.ofSeconds(l2TtlSeconds.getOrDefault(cacheName, l2DefaultTtlSeconds));
    }
}
