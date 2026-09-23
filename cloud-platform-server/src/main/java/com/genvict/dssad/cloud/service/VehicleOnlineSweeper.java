package com.genvict.dssad.cloud.service;

import com.genvict.dssad.cloud.common.schedule.DistributedTaskLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 车辆在线状态回落扫描器（P-02：{@code markOfflineVehicles} 此前从未被调用）。
 *
 * <p><b>缺陷背景</b>：车辆的「上线」由心跳实时驱动（{@code touchHeartbeat} 每秒触发），
 * 但「下线」没有任何调用方 —— 车辆熄火/驶出信号区后，列表与大屏上的在线状态
 * <b>永远停留在在线</b>，监管侧看到的在线数虚高，运维也无法据此发现掉线车辆。
 *
 * <p><b>为什么用轮询而不是延迟队列</b>：判定「离线」的依据是
 * {@code last_base_at < now - 2min}（阈值定义在 {@link VehicleService#OFFLINE_THRESHOLD}），
 * 本质是<b>对全量在线车辆做一次条件收敛</b>。用一条批量 UPDATE 每 30s 收敛一次，
 * 状态最多滞后 2.5 分钟 —— 对监管场景完全够用；若为此引入每车一个的延迟任务，
 * 千车规模下调度器本身就是新的故障点。
 *
 * <p><b>多实例语义</b>：批量 UPDATE 幂等，但 N 个实例同时扫属无谓重复，
 * 故套 {@link DistributedTaskLock}（锁 TTL 2 分钟 ≫ 任务实际耗时亚秒级）。
 *
 * <p><b>为何 fixedDelay 而非 cron</b>：这是持续性的状态收敛，不是每日批处理；
 * fixedDelay 保证上一轮结束后再计时，不会出现任务慢导致的堆积触发。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VehicleOnlineSweeper {

    /** 扫描间隔：离线判定的最大滞后 ≈ 阈值 2 分钟 + 本间隔 30 秒。 */
    static final long SWEEP_INTERVAL_MILLIS = 30_000L;

    private final VehicleService vehicleService;
    private final DistributedTaskLock taskLock;

    /** 累计执行轮数（拿到锁并实际执行的轮次）。 */
    private final AtomicLong sweepCount = new AtomicLong();
    /** 累计置为离线的车辆数（监控「车辆掉线速率」用）。 */
    private final AtomicLong offlineTotal = new AtomicLong();
    /** 最近一轮实际置离线的台数（连续多轮为 0 属正常；持续 >0 说明车队在大面积掉线）。 */
    private volatile long lastOfflineCount = 0L;
    /** 最近一轮执行时间（实例级）。 */
    private volatile Instant lastSweepAt;

    /** 每 30 秒收敛一次在线状态（启动延迟 45s：避开应用启动期的连接初始化高峰）。 */
    @Scheduled(fixedDelay = SWEEP_INTERVAL_MILLIS, initialDelay = 45_000L)
    public void sweep() {
        boolean executed = taskLock.runWithLock("vehicle-offline-sweep", Duration.ofMinutes(2),
                () -> lastOfflineCount = vehicleService.markOfflineVehicles());
        if (executed) {
            offlineTotal.addAndGet(lastOfflineCount);
            sweepCount.incrementAndGet();
            lastSweepAt = Instant.now();
        }
    }

    /** 累计执行轮数（监控端点用）。 */
    public long sweepCount() {
        return sweepCount.get();
    }

    /** 累计置离线车辆数。 */
    public long offlineTotal() {
        return offlineTotal.get();
    }

    /** 最近一轮置离线台数。 */
    public long lastOfflineCount() {
        return lastOfflineCount;
    }

    /** 最近一轮执行时间（从未执行过返回 {@code null}）。 */
    public Instant lastSweepAt() {
        return lastSweepAt;
    }
}
