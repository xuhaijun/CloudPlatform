package com.genvict.dssad.cloud.domain.repository;

import com.genvict.dssad.cloud.domain.entity.MapBarrier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 交通阻断设施仓储。 */
public interface MapBarrierRepository extends JpaRepository<MapBarrier, Long> {

    /** 幂等键查询：同一阻断设施同一天只应有一条。 */
    Optional<MapBarrier> findByBarrierIdAndEffectiveDate(String barrierId, LocalDate effectiveDate);

    List<MapBarrier> findByEffectiveDate(LocalDate effectiveDate);

    Page<MapBarrier> findByEffectiveDateOrderByBarrierTypeAsc(LocalDate effectiveDate, Pageable pageable);

    List<MapBarrier> findByAreaCodeAndEffectiveDate(String areaCode, LocalDate effectiveDate);

    long countByEffectiveDate(LocalDate effectiveDate);
}
