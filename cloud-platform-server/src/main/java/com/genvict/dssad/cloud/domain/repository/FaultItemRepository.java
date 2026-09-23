package com.genvict.dssad.cloud.domain.repository;

import com.genvict.dssad.cloud.domain.entity.FaultItem;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/** 故障明细仓储。 */
public interface FaultItemRepository extends JpaRepository<FaultItem, Long> {

    List<FaultItem> findByVinOrderByErrorTimestampDesc(String vin, Pageable pageable);

    long countByErrorCodeAndErrorTimestampAfter(String errorCode, Long afterTs);

    /**
     * 故障码 Top N 聚合（库内聚合，避免把明细全捞到内存再 GroupBy）。
     *
     * @return 每行为 {@code [errorCode, count, maxSeverity]}
     */
    @Query("""
            select i.errorCode, count(i), max(i.errorSeverity)
            from FaultItem i
            where i.faultRecord.reportedAt >= :since
            group by i.errorCode
            order by count(i) desc
            """)
    List<Object[]> topErrorCodes(@Param("since") Instant since, Pageable limit);

    /** 按车辆聚合故障数（找「故障最多的车」，用于重点运维名单）。 */
    @Query("""
            select i.vin, count(i)
            from FaultItem i
            where i.faultRecord.reportedAt >= :since
            group by i.vin
            order by count(i) desc
            """)
    List<Object[]> topFaultVehicles(@Param("since") Instant since, Pageable limit);
}
