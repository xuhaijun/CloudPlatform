package com.genvict.dssad.cloud.common.store;

import java.time.Duration;
import java.util.Optional;

/**
 * 轻量键值状态存储抽象。
 *
 * <p>平台把三类「瞬时状态」放在这里，而不是反复打数据库：
 * <ol>
 *   <li><b>报文去重</b>：车端重发是常态，用 {@link #setIfAbsent} 实现幂等；</li>
 *   <li><b>最新车辆状态</b>：1Hz 数据不能每次都查库，列表页/大屏读缓存；</li>
 *   <li><b>限流计数与访问令牌</b>。</li>
 * </ol>
 *
 * <p>两种实现：
 * <ul>
 *   <li>{@link InMemoryStateStore}（默认）：单机内存，零外部依赖，用于本地开发、单元测试、单机部署；</li>
 *   <li>{@link RedisStateStore}：分布式实现，多实例部署时<b>必须</b>启用，
 *       否则限流与去重会因实例各自计数而失效。</li>
 * </ul>
 */
public interface StateStore {

    /** 写入并设置有效期。 */
    void put(String key, String value, Duration ttl);

    /** 读取。 */
    Optional<String> get(String key);

    /**
     * 仅当键不存在时写入（SETNX + EXPIRE 语义）。
     *
     * @return 写入成功（键原本不存在）返回 {@code true}
     */
    boolean setIfAbsent(String key, String value, Duration ttl);

    /**
     * 自增计数并在首次创建时设置有效期。
     *
     * @return 自增后的值
     */
    long increment(String key, Duration ttl);

    /** 删除。 */
    void delete(String key);

    /** 当前时间戳（毫秒），便于实现方注入可控时钟做测试。 */
    default long nowMillis() {
        return System.currentTimeMillis();
    }
}
