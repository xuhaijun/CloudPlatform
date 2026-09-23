package com.genvict.dssad.cloud.common.schedule;

import com.genvict.dssad.cloud.common.store.StateStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * 定时任务的分布式锁（P-01/O-07：多实例部署前必须）。
 *
 * <p><b>为什么需要</b>：{@code @Scheduled} 默认在<b>每个</b>实例上都会触发。
 * 单机部署毫无问题；一旦水平扩容到多实例，三个 cron 任务（地图拉取、留痕清理、
 * 遥测清理）会同时跑 N 份 —— 重复拉取浪费出网配额尚可忍受，
 * 多实例同时分批 DELETE 同一张表则会互相争抢行锁、放大主从延迟。
 *
 * <h2>实现：SETNX + token 比对释放</h2>
 * <pre>
 *   抢锁：SET lock:sched:{task} = {uuid} NX EX {ttl}   → 成功即获得执行权
 *   执行：任务本体
 *   释放：GET 比对值是自己写入的 uuid 才 DEL（防止误删他人锁）
 * </pre>
 *
 * <p><b>已知的不严格处（刻意取舍，不引入 Redisson）</b>：
 * <ul>
 *   <li><b>释放不是原子的</b>：GET 与 DEL 之间存在竞态窗口 —— 若任务执行超过 TTL，
 *       锁已被其它实例抢走，此时本实例的 DEL 可能误删新锁。缓解：TTL 必须
 *       远大于任务最坏耗时（见各接入点的取值），且所有接入任务<b>幂等</b>，
 *       极端情况下重复执行一次无业务后果。</li>
 *   <li><b>无看门狗续租</b>：任务超 TTL 后可能出现双实例并发执行。
 *       对幂等批处理而言「偶尔重复」远优于「永不执行」，不值得为此引入依赖。</li>
 * </ul>
 *
 * <h2>降级语义（与平台 fail-open 哲学一致）</h2>
 * <ul>
 *   <li><b>单机部署</b>（{@code dssad.redis.enabled=false}，内存 StateStore）：
 *       锁恒可获取，行为退化为「无锁直通」—— 与改造前完全一致；</li>
 *   <li><b>Redis 异常</b>：{@link StateStore#setIfAbsent} 降级返回 {@code true}
 *       （放行），多实例都会执行 —— 依赖任务幂等兜底；
 *       「存储挂了导致任务永不执行」才是更严重的事故（清理任务停摆 → 表无限膨胀）。</li>
 * </ul>
 *
 * <h2>哪些任务不该用它</h2>
 * 秒级任务（{@code MqttAckRetryScheduler}、{@code TelemetryService} 的 1s 循环）
 * 消费的是<b>本实例内存中的缓冲/注册表</b>，属于「实例本地任务」：
 * 若加全局锁，抢不到锁的实例上的内存缓冲将永远无人消费，数据静默积压。
 * 只有「作用于共享资源（DB/Redis/远端）」的批处理才需要互斥。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DistributedTaskLock {

    /** 锁键前缀：与限流（rl:）、去重（dedup:）等业务键在命名空间上隔离。 */
    private static final String KEY_PREFIX = "lock:sched:";

    private final StateStore stateStore;

    /**
     * 在分布式锁保护下执行任务。
     *
     * @param taskKey 任务标识（同一任务的各实例必须相同）
     * @param ttl     锁的自动过期时间，必须显著大于任务最坏耗时（兜底防实例崩溃后死锁）
     * @param task    任务本体（要求幂等：超 TTL 被抢占时可能双实例并发）
     * @return 本实例是否实际执行了任务（{@code false} = 锁被其它实例持有，本轮跳过）
     */
    public boolean runWithLock(String taskKey, Duration ttl, Runnable task) {
        String key = KEY_PREFIX + taskKey;
        String token = UUID.randomUUID().toString();
        if (!stateStore.setIfAbsent(key, token, ttl)) {
            log.debug("[任务锁] {} 正被其它实例执行，本轮跳过", taskKey);
            return false;
        }
        try {
            task.run();
            return true;
        } finally {
            release(key, token, taskKey);
        }
    }

    /**
     * 释放锁：只有值仍是自己写入的 token 才删除。
     *
     * <p>「GET 比对 + DEL」非原子（见类注释），但对幂等批处理是足够安全的简化。
     */
    private void release(String key, String token, String taskKey) {
        try {
            stateStore.get(key).filter(token::equals).ifPresent(ignored -> stateStore.delete(key));
        } catch (RuntimeException e) {
            // 释放失败只影响下一轮的等待时长（最多等 TTL 过期），不影响任务结果
            log.warn("[任务锁] {} 释放失败，将等待 TTL 过期", taskKey, e);
        }
    }
}
