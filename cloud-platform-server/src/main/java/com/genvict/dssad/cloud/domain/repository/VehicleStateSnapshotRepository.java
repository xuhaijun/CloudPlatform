package com.genvict.dssad.cloud.domain.repository;

import com.genvict.dssad.cloud.domain.entity.VehicleStateSnapshot;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/** 车辆低频运行状态仓储。 */
public interface VehicleStateSnapshotRepository extends JpaRepository<VehicleStateSnapshot, Long> {

    Page<VehicleStateSnapshot> findByVinAndReportedAtBetweenOrderByReportedAtDesc(
            String vin, Instant from, Instant to, Pageable pageable);

    Optional<VehicleStateSnapshot> findFirstByVinOrderByReportedAtDesc(String vin);

    long countByReportedAtAfter(Instant since);

    /**
     * 分批删除超期运行状态流水（保留策略的执行语句）。
     *
     * <p>与 {@link VehicleTrackPointRepository#deleteBatchBefore} 同款：native + LIMIT
     * 才能避免「一条 DELETE 清空几个月历史」造成的长事务与复制延迟。
     *
     * <p>方法上的 {@code @Transactional} 不是装饰：Spring Data 的 {@code @Modifying}
     * 查询<b>不会</b>自动获得写事务（只有 {@code SimpleJpaRepository} 的内建 CRUD 方法有），
     * 若调用方也没有事务，执行时会直接抛 {@code TransactionRequiredException}。
     * 标在仓储方法而不是服务层，是为了保证「每批一个事务」—— 服务层的循环若标注
     * {@code @Transactional}，几千个批次会被合并成一个巨型事务，分批就白做了。
     * 走 {@code idx_state_vin_time} 的前导列无法生效（条件只有时间），
     * 本表日增量约 1/60 于轨迹表，全表扫描风险可接受；若后续成为瓶颈，
     * 按 {@code db/schema-mysql.sql} 文末的「可选增强」加时间列单列索引即可。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "delete from t_vehicle_state_snapshot where reported_at < :before limit :batchSize",
            nativeQuery = true)
    int deleteBatchBefore(@Param("before") Instant before, @Param("batchSize") int batchSize);
}
