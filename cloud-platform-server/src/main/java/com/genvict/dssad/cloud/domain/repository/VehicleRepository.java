package com.genvict.dssad.cloud.domain.repository;

import com.genvict.dssad.cloud.domain.entity.Vehicle;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** 车辆主档仓储。 */
public interface VehicleRepository extends JpaRepository<Vehicle, String>, JpaSpecificationExecutor<Vehicle> {

    Page<Vehicle> findByEnterpriseId(String enterpriseId, Pageable pageable);

    List<Vehicle> findByVehicleType(String vehicleType);

    List<Vehicle> findByVinIn(Collection<String> vins);

    /** 在线车辆数。 */
    long countByOnlineTrue();

    /** 按类型统计在线数，用于大屏。 */
    @Query("select v.vehicleType, count(v) from Vehicle v where v.online = true group by v.vehicleType")
    List<Object[]> countOnlineGroupByType();

    /**
     * 更新车辆心跳时间并置为在线。
     *
     * <p>用 {@code @Modifying} 单条 UPDATE 而非「查实体→改字段→保存」：
     * 低频基础信息 1s 一条，若每次都加载整行再做脏检查写回，会产生大量无意义的
     * SELECT + 全列 UPDATE。这里只更新两个必要列（高频路径优化）。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Vehicle v set v.online = true, v.lastBaseAt = :at, v.updatedAt = :at where v.vin = :vin")
    int touchOnline(@Param("vin") String vin, @Param("at") Instant at);

    /** 把心跳超时的车辆批量置为离线（由定时任务调用，避免每台车一次 UPDATE）。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Vehicle v set v.online = false where v.online = true and v.lastBaseAt < :deadline")
    int markOfflineBefore(@Param("deadline") Instant deadline);

    /** 事故/故障计数自增（避免读改写竞争）。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Vehicle v set v.accidentCount = v.accidentCount + 1 where v.vin = :vin")
    int increaseAccidentCount(@Param("vin") String vin);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Vehicle v set v.faultCount = v.faultCount + :delta where v.vin = :vin")
    int increaseFaultCount(@Param("vin") String vin, @Param("delta") long delta);

    boolean existsByVin(String vin);
}
