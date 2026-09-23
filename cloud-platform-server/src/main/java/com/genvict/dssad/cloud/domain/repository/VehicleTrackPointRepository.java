package com.genvict.dssad.cloud.domain.repository;

import com.genvict.dssad.cloud.domain.entity.VehicleTrackPoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 车辆轨迹仓储。
 *
 * <p>轨迹查询的两个关键约束（都体现在方法签名里）：
 * <ul>
 *   <li><b>必须带时间范围</b>：1Hz 数据一天 8.6 万点/车，不加时间条件会拖垮库；</li>
 *   <li><b>必须限制条数</b>：即使加了时间范围，跨天查询仍可能是几十万点，
 *       故提供 {@code limit} 参数配合服务层的「抽稀」策略（见
 *       {@code TrackSimplifyService}：先判总量，超阈值则按时间窗口降采样 + Douglas-Peucker 抽稀）。</li>
 * </ul>
 */
public interface VehicleTrackPointRepository extends JpaRepository<VehicleTrackPoint, Long> {

    /** 按时间范围升序拉取轨迹点（限制条数）。 */
    @Query(value = """
            select p from VehicleTrackPoint p
            where p.vin = :vin and p.ts between :fromTs and :toTs
            order by p.ts asc
            """)
    List<VehicleTrackPoint> findTrack(@Param("vin") String vin,
                                      @Param("fromTs") long fromTs,
                                      @Param("toTs") long toTs,
                                      org.springframework.data.domain.Pageable limit);

    /** 统计时间范围内点数，用于决定是否需要抽稀。 */
    long countByVinAndTsBetween(String vin, long fromTs, long toTs);

    /** 时间范围内最后一条（当前位置）。用派生查询而非 JPQL，避免 JPQL 不支持 {@code limit} 的问题。 */
    VehicleTrackPoint findFirstByVinAndTsLessThanEqualOrderByTsDesc(String vin, long toTs);

    /** 按时间窗口降采样：SQL 侧先做粗筛，把返回行数控制在万级以内。 */
    @Query(value = """
            select p from VehicleTrackPoint p
            where p.vin = :vin and p.ts between :fromTs and :toTs
              and mod(p.ts, :bucketMillis) < 1000
            order by p.ts asc
            """)
    List<VehicleTrackPoint> findTrackBucketed(@Param("vin") String vin,
                                              @Param("fromTs") long fromTs,
                                              @Param("toTs") long toTs,
                                              @Param("bucketMillis") long bucketMillis,
                                              org.springframework.data.domain.Pageable limit);

    /**
     * 分批删除超期轨迹点（保留策略的执行语句）。
     *
     * <p><b>为什么必须是 native + LIMIT</b>：本表日增可达千万行，一条不带 LIMIT 的
     * {@code DELETE ... WHERE ts < ?} 会一次性删除上亿行 —— 事务长时间持锁、undo 日志暴涨、
     * 主从复制严重延迟，与"清理历史数据"的初衷（释放空间）恰好相反。
     * 加上 {@code LIMIT} 后每次只删一批，由调用方循环执行，
     * 配合 MySQL 的「边删边返回」语义可把单次持锁控制在毫秒级。
     *
     * <p>JPQL 不支持 {@code LIMIT}，故用 {@code nativeQuery = true}。
     * 语句中<b>不允许出现 ORDER BY</b>：MySQL 的 {@code DELETE ... LIMIT} 与
     * {@code ORDER BY} 互斥。
     *
     * <p>走 {@code idx_track_ts}（ts 单列索引）而不是 {@code idx_track_vin_ts}：
     * 删除条件只有时间，无 VIN，单列索引才能让优化器做范围扫描。
     *
     * @param tsBefore  时间阈值（毫秒时间戳），早于该值的点位将被删除
     * @param batchSize 单批删除上限
     * @return 实际删除行数；返回 {@code batchSize} 表示可能仍有残留，调用方应继续下一批
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "delete from t_vehicle_track_point where ts < :tsBefore limit :batchSize",
            nativeQuery = true)
    int deleteBatchBefore(@Param("tsBefore") long tsBefore, @Param("batchSize") int batchSize);
}
