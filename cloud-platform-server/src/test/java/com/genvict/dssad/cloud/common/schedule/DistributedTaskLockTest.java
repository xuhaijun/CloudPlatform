package com.genvict.dssad.cloud.common.schedule;

import com.genvict.dssad.cloud.common.store.InMemoryStateStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分布式任务锁的行为测试。
 *
 * <p>用真实 {@link InMemoryStateStore} 而不是 mock：锁的语义就是
 * 「SETNX 成功与否」，内存实现的判定路径与 Redis 完全同构
 * （Redis 实现只是把同样的判定放到服务端），单测验证行为约定即可。
 */
@DisplayName("DistributedTaskLock：定时任务互斥")
class DistributedTaskLockTest {

    @Test
    @DisplayName("锁空闲时拿到锁并执行任务")
    void executesWhenLockFree() {
        DistributedTaskLock lock = new DistributedTaskLock(new InMemoryStateStore());
        AtomicInteger executed = new AtomicInteger();

        boolean ran = lock.runWithLock("task-a", Duration.ofMinutes(1), executed::incrementAndGet);

        assertTrue(ran);
        assertEquals(1, executed.get());
    }

    @Test
    @DisplayName("锁被其它实例持有时跳过本轮，任务不执行")
    void skipsWhenLockHeld() {
        InMemoryStateStore store = new InMemoryStateStore();
        DistributedTaskLock lock = new DistributedTaskLock(store);
        // 模拟其它实例持锁（直接占住底层键，模拟「另一个进程」）
        store.setIfAbsent("lock:sched:task-a", "other-instance-token", Duration.ofMinutes(1));
        AtomicInteger executed = new AtomicInteger();

        boolean ran = lock.runWithLock("task-a", Duration.ofMinutes(1), executed::incrementAndGet);

        assertFalse(ran, "锁被占时必须跳过，否则多实例重复执行");
        assertEquals(0, executed.get());
    }

    @Test
    @DisplayName("任务执行完释放锁，下一轮（或其它实例）可再次获取")
    void releasesAfterRun() {
        DistributedTaskLock lock = new DistributedTaskLock(new InMemoryStateStore());
        AtomicInteger executed = new AtomicInteger();

        lock.runWithLock("task-a", Duration.ofMinutes(1), executed::incrementAndGet);
        boolean secondRun = lock.runWithLock("task-a", Duration.ofMinutes(1), executed::incrementAndGet);

        assertTrue(secondRun, "释放后必须能重新获取，否则任务永久卡死");
        assertEquals(2, executed.get());
    }

    @Test
    @DisplayName("任务抛异常时锁仍被释放（finally 保证），异常向上传播")
    void releasesEvenWhenTaskFails() {
        DistributedTaskLock lock = new DistributedTaskLock(new InMemoryStateStore());

        assertThrows(IllegalStateException.class,
                () -> lock.runWithLock("task-a", Duration.ofMinutes(1), () -> {
                    throw new IllegalStateException("boom");
                }));

        AtomicInteger executed = new AtomicInteger();
        boolean recovered = lock.runWithLock("task-a", Duration.ofMinutes(1), executed::incrementAndGet);
        assertTrue(recovered, "任务失败后锁必须已释放，否则该任务从此全部跳过");
        assertEquals(1, executed.get());
    }

    @Test
    @DisplayName("不同任务键互不干扰")
    void differentTasksAreIndependent() {
        DistributedTaskLock lock = new DistributedTaskLock(new InMemoryStateStore());
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();

        lock.runWithLock("task-a", Duration.ofMinutes(1), a::incrementAndGet);
        boolean bRan = lock.runWithLock("task-b", Duration.ofMinutes(1), b::incrementAndGet);

        assertTrue(bRan, "task-a 持锁不得影响 task-b");
        assertEquals(1, b.get());
    }
}
