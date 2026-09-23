package com.genvict.dssad.cloud.domain.repository;

import com.genvict.dssad.cloud.domain.entity.VehicleStaticParam;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 车辆准静态参数流水仓储。 */
public interface VehicleStaticParamRepository extends JpaRepository<VehicleStaticParam, Long> {

    Page<VehicleStaticParam> findByVinOrderByReportedAtDesc(String vin, Pageable pageable);

    Optional<VehicleStaticParam> findFirstByVinOrderByReportedAtDesc(String vin);
}
