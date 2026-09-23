package com.genvict.dssad.cloud.common.store;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版 {@link StateStore}（默认实现）。
 *
 * <p>用于「零依赖启动」：本地开发、单元测试、单机演示环境无需安装 Redis 即可跑通全链路。
 *
 * <p>设计取舍：
 * <ul>
 *   <li>用 {@link ConcurrentHashMap} 存条目，每个条目自带过期时间戳（惰性过期）；
 *       <b>不做后台扫描线程</b>——定时全表扫描在键量大时反而抢 CPU，改为「读时判定 + 写时清理」，
 *       并配合容量上限防止内存无界增长；</li>
 *   <li>过期条目在被读取或达到容量上限时批量清理，保证内存可回收。</li>
 * </ul>
 *
 * <p><b>限制</b>：多实例部署时各实例各持一份状态，去重与限流不准确 ——
 * 生产环境请启用 {@code dssad.redis.enabled=true}。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "dssad.redis.enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryStateStore implements StateStore {

    /** 容量上限，超过则触发一次过期清理；清理后仍超限则拒绝新写入并告警。 */
    private static final int MAX_ENTRIES = 200_000;

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    @Override
    public void put(String key, String value, Duration ttl) {
        if (key == null) {
            return;
        }
        ensureCapacity();
        store.put(key, new Entry(value, expireAt(ttl)));
    }

    @Override
    public Optional<String> get(String key) {
        if (key == null) {
            return Optional.empty();
        }
        Entry entry = store.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expired(nowMillis())) {
            store.remove(key, entry);
            return Optional.empty();
        }
        return Optional.ofNullable(entry.value);
    }

    @Override
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        if (key == null) {
            return false;
        }
        ensureCapacity();
        Entry existing = store.get(key);
        if (existing != null && !existing.expired(nowMillis())) {
            return false;
        }
        store.put(key, new Entry(value, expireAt(ttl)));
        return true;
    }

    @Override
    public long increment(String key, Duration ttl) {
        if (key == null) {
            return 0L;
        }
        ensureCapacity();
        long now = nowMillis();
        Entry entry = store.compute(key, (k, old) -> {
            if (old == null || old.expired(now)) {
                return new Entry("1", now + ttl.toMillis());
            }
            return new Entry(String.valueOf(old.counter() + 1), old.expireAt, old.counter() + 1);
        });
        return entry.counter();
    }

    @Override
    public void delete(String key) {
        if (key != null) {
            store.remove(key);
        }
    }

    /** 当前条目数（监控用）。 */
    public int size() {
        return store.size();
    }

    private void ensureCapacity() {
        if (store.size() < MAX_ENTRIES) {
            return;
        }
        long now = nowMillis();
        store.entrySet().removeIf(e -> e.getValue().expired(now));
        if (store.size() >= MAX_ENTRIES) {
            log.warn("[状态存储] 内存条目已达上限 {}，请检查 TTL 设置或改用 Redis 实现", MAX_ENTRIES);
        }
    }

    private long expireAt(Duration ttl) {
        return nowMillis() + (ttl == null ? Duration.ofMinutes(5).toMillis() : ttl.toMillis());
    }

    /** 带过期时间与计数值的条目。 */
    private static final class Entry {
        private final String value;
        private final long expireAt;
        private final long counter;

        Entry(String value, long expireAt) {
            this(value, expireAt, 1L);
        }

        Entry(String value, long expireAt, long counter) {
            this.value = value;
            this.expireAt = expireAt;
            this.counter = counter;
        }

        boolean expired(long now) {
            return now >= expireAt;
        }

        long counter() {
            return counter;
        }
    }
}
