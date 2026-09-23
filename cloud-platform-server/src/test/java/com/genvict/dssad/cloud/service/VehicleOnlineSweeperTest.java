package com.genvict.dssad.cloud.service;

import com.genvict.dssad.cloud.common.schedule.DistributedTaskLock;
import com.genvict.dssad.cloud.common.store.InMemoryStateStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 在线状态回落扫描器的行为测试（P-02 接线验证）。
 *
 * <p>重点验证两类约定：
 * <ol>
 *   <li>拿到锁时真正调用 {@code markOfflineVehicles} 且统计被累加 ——
 *       防止「接线了但没调用」这种 F-01 式缺陷复发；</li>
 *   <li>锁被占时完全跳过（不调用业务方法、统计不变）——
 *       多实例下只有持锁实例计入统计，跳过者不得产生副作用。</li>
 * </ol>
 */
@DisplayName("VehicleOnlineSweeper：在线状态回落")
class VehicleOnlineSweeperTest {

    @Test
    @DisplayName("拿到锁时执行回落并累加统计")
    void sweepsAndAccumulatesStats() {
        VehicleService vehicleService = mock(VehicleService.class);
        when(vehicleService.markOfflineVehicles()).thenReturn(3);
        VehicleOnlineSweeper sweeper = new VehicleOnlineSweeper(
                vehicleService, new DistributedTaskLock(new InMemoryStateStore()));

        sweeper.sweep();

        verify(vehicleService).markOfflineVehicles();
        assertEquals(1, sweeper.sweepCount());
        assertEquals(3, sweeper.offlineTotal());
        assertEquals(3, sweeper.lastOfflineCount());
        assertEquals(0, sweeper.lastSweepAt().getEpochSecond() == 0 ? 1 : 0, "lastSweepAt 应被记录");
    }

    @Test
    @DisplayName("多轮执行统计累加，无掉线轮次不歪曲累计值")
    void accumulatesAcrossSweeps() {
        VehicleService vehicleService = mock(VehicleService.class);
        when(vehicleService.markOfflineVehicles()).thenReturn(3, 0, 5);
        VehicleOnlineSweeper sweeper = new VehicleOnlineSweeper(
                vehicleService, new DistributedTaskLock(new InMemoryStateStore()));

        sweeper.sweep();
        sweeper.sweep();
        sweeper.sweep();

        assertEquals(3, sweeper.sweepCount());
        assertEquals(8, sweeper.offlineTotal(), "累计值 = 3 + 0 + 5");
        assertEquals(5, sweeper.lastOfflineCount());
    }

    @Test
    @DisplayName("锁被其它实例持有时完全跳过（不调业务、统计不变）")
    void skipsWhenLockHeld() {
        VehicleService vehicleService = mock(VehicleService.class);
        InMemoryStateStore store = new InMemoryStateStore();
        // 模拟其它实例持锁
        store.setIfAbsent("lock:sched:vehicle-offline-sweep", "other", Duration.ofMinutes(2));
        VehicleOnlineSweeper sweeper = new VehicleOnlineSweeper(
                vehicleService, new DistributedTaskLock(store));

        sweeper.sweep();

        verify(vehicleService, never()).markOfflineVehicles();
        assertEquals(0, sweeper.sweepCount());
        assertEquals(0, sweeper.offlineTotal());
        assertNull(sweeper.lastSweepAt(), "未执行轮次不得伪造执行时间");
    }
}
