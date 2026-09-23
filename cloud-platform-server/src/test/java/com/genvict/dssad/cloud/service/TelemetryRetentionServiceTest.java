package com.genvict.dssad.cloud.service;

import com.genvict.dssad.cloud.common.schedule.DistributedTaskLock;
import com.genvict.dssad.cloud.common.store.InMemoryStateStore;
import com.genvict.dssad.cloud.common.util.SnowflakeIdGenerator;
import com.genvict.dssad.cloud.config.AppProperties;
import com.genvict.dssad.cloud.domain.entity.VehicleStateSnapshot;
import com.genvict.dssad.cloud.domain.entity.VehicleTrackPoint;
import com.genvict.dssad.cloud.domain.repository.VehicleStateSnapshotRepository;
import com.genvict.dssad.cloud.domain.repository.VehicleTrackPointRepository;
import com.genvict.dssad.cloud.support.TestProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 遥测保留策略（数据清理）集成测试。
 *
 * <p><b>为什么必须是集成测试而不是 Mockito 单测</b>：清理语句是 native SQL
 * （{@code delete ... where ts < ? limit ?}），而 {@code LIMIT} 在 JPQL 里根本不存在。
 * 用 mock 掉仓储的写法能轻松通过，却完全无法证明这条 SQL 在真实数据库上语法正确 ——
 * 而它只在每天 03:30 执行一次，写错的代价是「清理任务静默失败数周后磁盘告警」。
 * 因此在 H2（MODE=MySQL）上真跑一遍：既覆盖语法，也覆盖 {@code @Transactional}
 * 是否真的挂在 {@code @Modifying} 方法上（漏标会直接抛 {@code TransactionRequiredException}）。
 *
 * <p>三个断言层层递进：
 * <ol>
 *   <li><b>只删超期数据</b>：保留期内的一条都不能少 —— 这是最危险的反向错误；</li>
 *   <li><b>分批生效</b>：批大小小于总行数时能被循环继续清空（验证循环终止条件）；</li>
 *   <li><b>关闭开关真的关闭</b>：保留天数为 0 时一行都不删（防止误配置把库清空）。</li>
 * </ol>
 *
 * <p>测试数据用完即清：H2 内存库在所有 Spring 上下文之间共享，
 * 若留下轨迹点，会影响其他用例对「轨迹条数」的断言。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("遥测保留策略：超期数据分批清理")
class TelemetryRetentionServiceTest {

    private static final String VIN = "TESTVINRETENTION01";

    @Autowired
    private VehicleTrackPointRepository trackPointRepository;

    @Autowired
    private VehicleStateSnapshotRepository stateSnapshotRepository;

    @Autowired
    private SnowflakeIdGenerator idGenerator;

    @AfterEach
    void cleanUp() {
        trackPointRepository.deleteAll();
        stateSnapshotRepository.deleteAll();
    }

    @Test
    @DisplayName("只删除超期轨迹点，保留期内数据零丢失")
    void purgeOnlyRemovesExpiredTrackPoints() {
        // 3 条「过期很久」（40 天前），2 条「还在保留期内」（1 天前）
        insertTrackPoints(40, 3);
        insertTrackPoints(1, 2);

        TelemetryRetentionService service = serviceWithRetention(30, 0, 100);
        TelemetryRetentionService.PurgeResult result = service.purgeExpired();

        assertEquals(3, result.deletedTrackPoints(), "应删除 3 条 40 天前的轨迹点");
        assertEquals(2, trackPointRepository.count(), "保留期内的 2 条轨迹点必须原样保留");
        assertEquals(30, result.trackPointRetentionDays(), "结果里回带生效的保留天数，便于断言配置真的被读到");
    }

    @Test
    @DisplayName("批大小小于待删行数时循环继续，直到清空")
    void purgeLoopsUntilAllExpiredRowsRemoved() {
        // 7 条超期数据 + 批大小 2 → 需要 4 批（2+2+2+1）
        insertTrackPoints(30, 7);

        TelemetryRetentionService service = serviceWithRetention(7, 0, 2);
        TelemetryRetentionService.PurgeResult result = service.purgeExpired();

        assertEquals(7, result.deletedTrackPoints(),
                "循环必须在「某批不足批大小」时才终止，否则会留下尾巴");
        assertEquals(0, trackPointRepository.count());
    }

    @Test
    @DisplayName("保留天数为 0 时不清理（防误配置清库）")
    void zeroRetentionDaysDisablesPurge() {
        insertTrackPoints(365, 5);
        insertStateSnapshots(365, 5);

        TelemetryRetentionService service = serviceWithRetention(0, 0, 100);
        TelemetryRetentionService.PurgeResult result = service.purgeExpired();

        assertEquals(0, result.deletedTrackPoints());
        assertEquals(0, result.deletedStateSnapshots());
        assertEquals(5, trackPointRepository.count(), "关闭清理时一行都不能删");
        assertEquals(5, stateSnapshotRepository.count());
    }

    @Test
    @DisplayName("状态流水按天保留，且与轨迹表互不干扰")
    void purgeStateSnapshotsIndependently() {
        insertTrackPoints(400, 3);
        insertStateSnapshots(400, 4);

        // 轨迹表关闭清理，只清状态表
        TelemetryRetentionService service = serviceWithRetention(0, 90, 100);
        TelemetryRetentionService.PurgeResult result = service.purgeExpired();

        assertEquals(0, result.deletedTrackPoints(), "轨迹表关闭时不应被动到");
        assertEquals(4, result.deletedStateSnapshots());
        assertEquals(3, trackPointRepository.count());
        assertEquals(0, stateSnapshotRepository.count());
    }

    // ==================== 辅助 ====================

    private TelemetryRetentionService serviceWithRetention(int trackDays, int stateDays, int batchSize) {
        AppProperties properties = TestProperties.withRetention(trackDays, stateDays, batchSize);
        // 测试直接调用 purgeExpired()（业务方法，不带锁）；锁组件配内存实现即可
        return new TelemetryRetentionService(trackPointRepository, stateSnapshotRepository, properties,
                new DistributedTaskLock(new InMemoryStateStore()));
    }

    /** 插入 {@code count} 条 {@code daysAgo} 天前的轨迹点。 */
    private void insertTrackPoints(int daysAgo, int count) {
        long ts = Instant.now().minus(Duration.ofDays(daysAgo)).toEpochMilli();
        List<VehicleTrackPoint> points = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            VehicleTrackPoint point = new VehicleTrackPoint();
            point.setId(idGenerator.nextId());
            point.setVin(VIN);
            // 每条错开 1 秒，避免完全同时间戳掩盖「按时间范围删除」的边界问题
            point.setTs(ts + i * 1000L);
            point.setLongitude(104.06 + i * 0.0001);
            point.setLatitude(30.66);
            point.setSpeed(20.0);
            points.add(point);
        }
        trackPointRepository.saveAll(points);
    }

    /** 插入 {@code count} 条 {@code daysAgo} 天前的运行状态流水。 */
    private void insertStateSnapshots(int daysAgo, int count) {
        Instant reportedAt = Instant.now().minus(Duration.ofDays(daysAgo));
        List<VehicleStateSnapshot> snapshots = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            VehicleStateSnapshot snapshot = new VehicleStateSnapshot();
            snapshot.setId(idGenerator.nextId());
            snapshot.setVin(VIN);
            snapshot.setReportedAt(reportedAt.plusSeconds(i * 60L));
            snapshot.setSoc(80.0);
            snapshot.setTemperature(125);
            snapshots.add(snapshot);
        }
        stateSnapshotRepository.saveAll(snapshots);
    }
}
