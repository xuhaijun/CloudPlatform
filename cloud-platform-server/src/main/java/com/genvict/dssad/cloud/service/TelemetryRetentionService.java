package com.genvict.dssad.cloud.service;

import com.genvict.dssad.cloud.config.AppProperties;
import com.genvict.dssad.cloud.domain.repository.VehicleStateSnapshotRepository;
import com.genvict.dssad.cloud.domain.repository.VehicleTrackPointRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.function.IntSupplier;

/**
 * 遥测流水保留策略执行器（数据生命周期管理）。
 *
 * <p><b>为什么需要它</b>：本平台有两张「只增不减」的流水表，且增长速率差了一个数量级：
 * <ul>
 *   <li>{@code t_vehicle_track_point}：低速无人车 1Hz 定位，1000 台车 ≈ <b>8640 万行/天</b>；</li>
 *   <li>{@code t_vehicle_state_snapshot}：60s 一次运行状态，1000 台车 ≈ <b>144 万行/天</b>。</li>
 * </ul>
 * 没有任何删除逻辑时，前者约两周即可把单表推到十亿行量级 —— 查询变慢、备份做不完、
 * 磁盘告警，最终只能停机人工清理，而人工清理往往就是一条把主库锁死的大 DELETE。
 * 因此清理必须<b>由应用按策略、分批、在业务低峰自动完成</b>。
 *
 * <p><b>为什么分批</b>：一条
 * {@code DELETE FROM t_vehicle_track_point WHERE ts < ?} 在亿级表上会长时间持有行锁、
 * 产生巨量 undo、并让从库延迟数小时。改成每批 {@code LIMIT N} 后，单次持锁在毫秒级，
 * 主从复制与在线查询几乎无感；代价只是耗时变长（这是优点：削峰）。
 *
 * <p><b>执行窗口</b>：每天 03:30。之所以不用凌晨 0 点，是为了避开
 * 「监管平台 00:05 地图增强补偿拉取」与「03:00 报文留痕清理」，
 * 让三个后台任务在时间上错开，避免同时争抢 IO。
 *
 * <p><b>安全阀</b>：单次运行最多执行 {@code MAX_BATCHES_PER_RUN} 批。若某天积压特别多
 * （例如刚上线保留策略、或曾经关停过清理任务），不设置上限会让一个调度周期跑上几小时；
 * 留一点尾巴到第二天继续清理，比「一次跑完」更安全。
 *
 * <p>执行结果会打 INFO 日志（含耗时与行数），可直接接日志告警：
 * 若连续多日删除 0 行且表在增长，说明保留天数配得过大或任务未生效。
 */
@Slf4j
@Service
public class TelemetryRetentionService {

    /** 单次运行的批次上限（安全阀）。按每批 5000 行、每批约 20ms 估算，单次运行不超过数分钟。 */
    private static final int MAX_BATCHES_PER_RUN = 2000;

    private final VehicleTrackPointRepository trackPointRepository;
    private final VehicleStateSnapshotRepository stateSnapshotRepository;
    private final AppProperties properties;

    public TelemetryRetentionService(VehicleTrackPointRepository trackPointRepository,
                                     VehicleStateSnapshotRepository stateSnapshotRepository,
                                     AppProperties properties) {
        this.trackPointRepository = trackPointRepository;
        this.stateSnapshotRepository = stateSnapshotRepository;
        this.properties = properties;
    }

    /** 每天 03:30 执行一次（错开 00:05 地图拉取与 03:00 留痕清理）。 */
    @Scheduled(cron = "0 30 3 * * ?")
    public void scheduledPurge() {
        PurgeResult result = purgeExpired();
        if (result.deletedTrackPoints() == 0 && result.deletedStateSnapshots() == 0) {
            // 无数据可清属正常情况（刚上线、或保留期设置得很长），用 debug 避免刷屏
            log.debug("[保留策略] 本轮无需清理，轨迹表保留 {} 天、状态表保留 {} 天",
                    result.trackPointRetentionDays(), result.stateSnapshotRetentionDays());
        }
    }

    /**
     * 执行一轮清理（供调度与测试调用）。
     *
     * <p>两张表分别独立处理：轨迹表日增是状态表的 60 倍，不应因为其中一张表出错
     * 就让另一张表的清理也失败。因此各自捕获异常并记录，最后汇总返回。
     *
     * @return 本轮实际删除行数与生效的保留天数
     */
    public PurgeResult purgeExpired() {
        AppProperties.Retention config = properties.retention();
        int batchSize = Math.max(1, config.batchSize());

        long deletedTrackPoints = purgeTrackPoints(config.trackPointDays(), batchSize);
        long deletedStateSnapshots = purgeStateSnapshots(config.stateSnapshotDays(), batchSize);

        if (deletedTrackPoints > 0 || deletedStateSnapshots > 0) {
            log.info("[保留策略] 已清理超期遥测：轨迹点 {} 行（保留 {} 天）、状态流水 {} 行（保留 {} 天）",
                    deletedTrackPoints, config.trackPointDays(),
                    deletedStateSnapshots, config.stateSnapshotDays());
        }
        return new PurgeResult(deletedTrackPoints, deletedStateSnapshots,
                config.trackPointDays(), config.stateSnapshotDays());
    }

    /** 分批删除超期轨迹点。 */
    private long purgeTrackPoints(int retentionDays, int batchSize) {
        if (retentionDays <= 0) {
            log.debug("[保留策略] 轨迹点保留天数配置为 {}，跳过清理（生产环境不应出现此配置）", retentionDays);
            return 0;
        }
        long deadline = Instant.now().minus(Duration.ofDays(retentionDays)).toEpochMilli();
        return purgeInBatches(() -> trackPointRepository.deleteBatchBefore(deadline, batchSize),
                batchSize, "轨迹点");
    }

    /** 分批删除超期运行状态流水。 */
    private long purgeStateSnapshots(int retentionDays, int batchSize) {
        if (retentionDays <= 0) {
            log.debug("[保留策略] 状态流水保留天数配置为 {}，跳过清理", retentionDays);
            return 0;
        }
        Instant before = Instant.now().minus(Duration.ofDays(retentionDays));
        return purgeInBatches(() -> stateSnapshotRepository.deleteBatchBefore(before, batchSize),
                batchSize, "状态流水");
    }

    /**
     * 通用分批删除循环。
     *
     * <p>每个批次走<b>独立事务</b>（事务边界标在仓储的 {@code @Modifying} 方法上），
     * 本方法刻意<b>不标注</b> {@code @Transactional}：一旦包成外层事务，
     * 几千个批次的删除会合并成一个巨型事务，分批就失去意义了。
     *
     * <p>终止条件有两个：某批返回的行数小于批大小（已清空）或达到批次上限（安全阀）。
     */
    private long purgeInBatches(IntSupplier deleteOneBatch, int batchSize, String tableLabel) {
        long total = 0;
        int batches = 0;
        try {
            while (batches < MAX_BATCHES_PER_RUN) {
                int deleted = deleteOneBatch.getAsInt();
                total += deleted;
                batches++;
                if (deleted < batchSize) {
                    break;
                }
            }
            if (batches >= MAX_BATCHES_PER_RUN) {
                log.warn("[保留策略] {} 单次清理达到批次上限 {}，剩余数据将在下次调度继续清理",
                        tableLabel, MAX_BATCHES_PER_RUN);
            }
        } catch (RuntimeException e) {
            // 单表失败不影响另一张表：记录已删行数后继续
            log.error("[保留策略] {} 清理中断，本轮已删除 {} 行，将于下次调度重试", tableLabel, total, e);
        }
        return total;
    }

    /**
     * 一轮清理的结果。
     *
     * @param deletedTrackPoints      删除的轨迹点数
     * @param deletedStateSnapshots   删除的状态流水数
     * @param trackPointRetentionDays 生效的轨迹保留天数（0 表示未启用）
     * @param stateSnapshotRetentionDays 生效的状态流水保留天数（0 表示未启用）
     */
    public record PurgeResult(long deletedTrackPoints,
                              long deletedStateSnapshots,
                              int trackPointRetentionDays,
                              int stateSnapshotRetentionDays) {

        /** 总删除行数。 */
        public long totalDeleted() {
            return deletedTrackPoints + deletedStateSnapshots;
        }
    }
}
