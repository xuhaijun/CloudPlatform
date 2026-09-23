package com.genvict.dssad.cloud.domain.repository;

import com.genvict.dssad.cloud.domain.entity.NavigationRoute;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 导航路径仓储。 */
public interface NavigationRouteRepository extends JpaRepository<NavigationRoute, Long> {

    Page<NavigationRoute> findByVinOrderByReportedAtDesc(String vin, Pageable pageable);

    Optional<NavigationRoute> findFirstByVinOrderByReportedAtDesc(String vin);
}
