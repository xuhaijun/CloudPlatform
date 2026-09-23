package com.genvict.dssad.cloud.common.store;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis 版 {@link StateStore}（多实例部署必须启用）。
 *
 * <p>通过 {@code dssad.redis.enabled=true} 开启。启用后：
 * <ul>
 *   <li>报文去重、限流计数、最新状态在多实例间<b>共享</b>，语义才正确；</li>
 *   <li>所有键统一带 {@code dssad:} 前缀，便于与同一 Redis 实例上的其他业务隔离与清理。</li>
 * </ul>
 *
 * <p>容错设计：Redis 抖动时<b>降级放行</b>而不是抛异常中断业务 ——
 * 去重失败最多导致一次重复处理（业务侧有幂等），限流失败最多导致短暂超限，
 * 但若直接抛异常，会导致正常报文全部处理失败，代价更大。降级会打 error 日志以便告警。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "dssad.redis.enabled", havingValue = "true")
public class RedisStateStore implements StateStore {

    private static final String KEY_PREFIX = "dssad:";

    private final StringRedisTemplate redisTemplate;

    public RedisStateStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key(key), value, ttl);
        } catch (RuntimeException e) {
            log.error("[状态存储] Redis 写入失败，已降级忽略 key={}", key, e);
        }
    }

    @Override
    public Optional<String> get(String key) {
        try {
            return Optional.ofNullable(redisTemplate.opsForValue().get(key(key)));
        } catch (RuntimeException e) {
            log.error("[状态存储] Redis 读取失败，已降级为未命中 key={}", key, e);
            return Optional.empty();
        }
    }

    @Override
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        try {
            Boolean success = redisTemplate.opsForValue().setIfAbsent(key(key), value, ttl);
            return Boolean.TRUE.equals(success);
        } catch (RuntimeException e) {
            log.error("[状态存储] Redis SETNX 失败，已降级放行 key={}", key, e);
            return true;
        }
    }

    @Override
    public long increment(String key, Duration ttl) {
        try {
            Long value = redisTemplate.opsForValue().increment(key(key));
            if (value != null && value == 1L) {
                // 首次创建时补设过期时间：用固定窗口而非滑动窗口，
                // 单次 RTT 即可完成限流判定（权衡说明见 RateLimiter 类注释）
                redisTemplate.expire(key(key), ttl);
            }
            return value == null ? 0L : value;
        } catch (RuntimeException e) {
            log.error("[状态存储] Redis INCR 失败，已降级放行 key={}", key, e);
            return 0L;
        }
    }

    @Override
    public void delete(String key) {
        try {
            redisTemplate.delete(key(key));
        } catch (RuntimeException e) {
            log.error("[状态存储] Redis 删除失败 key={}", key, e);
        }
    }

    private String key(String raw) {
        return KEY_PREFIX + raw;
    }
}
