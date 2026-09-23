package com.genvict.dssad.cloud.domain.repository;

import com.genvict.dssad.cloud.domain.entity.FaultRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 故障上报仓储。 */
public interface FaultRecordRepository extends JpaRepository<FaultRecord, Long> {

    Page<FaultRecord> findByVinOrderByReportedAtDesc(String vin, Pageable pageable);

    Page<FaultRecord> findByReportedAtBetweenOrderByReportedAtDesc(Instant from, Instant to, Pageable pageable);

    /** 按最严重等级过滤（6~7 为严重/不可恢复，是运维重点）。 */
    Page<FaultRecord> findByMaxSeverityGreaterThanEqualOrderByReportedAtDesc(Integer minSeverity, Pageable pageable);

    /**
     * 详情查询：一次性把明细抓出来。
     *
     * <p>{@code items} 默认 LAZY，如果直接在 Controller 里访问会触发 N+1；
     * 用 {@link EntityGraph} 让这一条查询就带出明细；而列表查询<b>不带</b>明细，
     * 保证列表页 SQL 干净。
     *
     * <p>注意：必须用 {@code @Query} 显式声明，Spring Data 的派生查询会把
     * {@code findWithItemsById} 解析成名为 {@code withItems} 的属性而导致启动报错。
     */
    @EntityGraph(attributePaths = "items")
    @Query("select f from FaultRecord f where f.id = :id")
    Optional<FaultRecord> findWithItems(@Param("id") Long id);

    /**
     * 组合条件分页查询（筛选条件之间是「与」关系）。
     *
     * <p><b>为什么要专门写这条查询</b>：早期实现用「if / else if」在多个派生查询之间二选一，
     * 结果是「同时按车辆与严重等级筛选」时后一个条件被静默忽略 ——
     * 前端选了「严重等级 ≥ 6」，列表里却照旧出现等级 5 的记录，看起来像数据错乱。
     * 所有条件都允许为 {@code null}（表示不限制），从而支持任意组合。
     */
    @Query("""
            select f from FaultRecord f
             where (:vin is null or f.vin = :vin)
               and (:minSeverity is null or f.maxSeverity >= :minSeverity)
               and (:from is null or f.reportedAt >= :from)
               and (:to is null or f.reportedAt <= :to)
             order by f.reportedAt desc
            """)
    Page<FaultRecord> search(@Param("vin") String vin,
                             @Param("minSeverity") Integer minSeverity,
                             @Param("from") Instant from,
                             @Param("to") Instant to,
                             Pageable pageable);

    long countByReportedAtAfter(Instant since);

    long countByMaxSeverityGreaterThanEqualAndReportedAtAfter(Integer minSeverity, Instant since);
}
