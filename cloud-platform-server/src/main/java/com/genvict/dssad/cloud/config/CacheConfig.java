package com.genvict.dssad.cloud.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genvict.dssad.cloud.common.cache.CacheNames;
import com.genvict.dssad.cloud.common.cache.TwoLevelCache;
import com.genvict.dssad.cloud.common.cache.TwoLevelCacheManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;

/**
 * 缓存装配。
 *
 * <p>用自研的 {@link TwoLevelCacheManager}（Caffeine L1 + Redis L2）替换
 * Spring Boot 自动配置的单级缓存：
 * <ul>
 *   <li>本 Bean 存在时，Boot 的 {@code CaffeineCacheConfiguration}
 *       因 {@code @ConditionalOnMissingBean(CacheManager.class)} 自动退让；</li>
 *   <li>{@code dssad.cache.enabled=false}（单测环境）时本配置整体不生效，
 *       由 {@code spring.cache.type} 决定 —— 单测显式设为 {@code none}，
 *       走 {@code NoOpCacheManager}，从而<b>杜绝跨用例的缓存残留</b>
 *       （否则 A 用例注入的报文会被 B 用例从缓存里读到旧统计值，测试随机失败）。</li>
 * </ul>
 *
 * <p>L2 的开启条件与 {@link com.genvict.dssad.cloud.common.store.RedisStateStore}
 * 保持同一个开关 {@code dssad.redis.enabled}：既然部署方已经决定用 Redis 做去重与限流，
 * 就没有理由再单独给缓存配一个开关 —— 少一个开关就少一种配错的可能。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "dssad.cache.enabled", havingValue = "true", matchIfMissing = true)
public class CacheConfig {

    @Bean
    @Primary
    public CacheManager cacheManager(ObjectMapper objectMapper,
                                     CacheProperties properties,
                                     ObjectProvider<StringRedisTemplate> redisProvider,
                                     @org.springframework.beans.factory.annotation.Value("${dssad.redis.enabled:false}")
                                     boolean redisEnabled) {

        StringRedisTemplate redis = redisEnabled ? redisProvider.getIfAvailable() : null;
        // 声明开启却没拿到 Bean 属于装配异常，必须显式告警，否则会「以为有二级缓存，其实只有一级」
        if (redisEnabled && redis == null) {
            log.error("[缓存] dssad.redis.enabled=true 但未找到 StringRedisTemplate，"
                    + "L2 分布式缓存不可用，已降级为仅本地一级缓存");
        }

        TwoLevelCacheManager manager = new TwoLevelCacheManager(
                Set.of(CacheNames.ALL),
                name -> new TwoLevelCache(
                        name,
                        com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                                .maximumSize(properties.l1MaxSize())
                                .expireAfterWrite(properties.l1Ttl(name))
                                .build(),
                        redis,
                        properties.l2Ttl(name),
                        objectMapper),
                redis != null);

        log.info("[缓存] 二级缓存已装配：L1=Caffeine(maxSize={}){}；声明缓存={}",
                properties.l1MaxSize(),
                redis != null ? "，L2=Redis" : "，L2=未启用（单机模式）",
                Set.of(CacheNames.ALL));
        return manager;
    }
}
