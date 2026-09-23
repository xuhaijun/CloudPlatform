package com.genvict.dssad.cloud.domain.repository;

import com.genvict.dssad.cloud.domain.entity.RemoteDrivingRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/** 远程驾驶接管记录仓储。 */
public interface RemoteDrivingRecordRepository extends JpaRepository<RemoteDrivingRecord, Long> {

    Page<RemoteDrivingRecord> findByVinOrderByOccurredAtDesc(String vin, Pageable pageable);

    Page<RemoteDrivingRecord> findByOccurredAtBetweenOrderByOccurredAtDesc(
            Instant from, Instant to, Pageable pageable);

    /** 该车最近一次「未结束」的接管（用于把 type=2 的结束报文配对回填）。 */
    /**
     * 组合条件分页查询（筛选条件之间是「与」关系）。
     *
     * <p>远端「进行中/已结束」开关必须与「按车筛选」同时生效：
     * 早期实现二选一，导致按车查询时 {@code closed} 参数被忽略，
     * 「进行中」列表里混进已结束的接管记录。
     */
    @Query("""
            select r from RemoteDrivingRecord r
             where (:vin is null or r.vin = :vin)
               and (:closed is null or r.closed = :closed)
               and (:from is null or r.occurredAt >= :from)
               and (:to is null or r.occurredAt <= :to)
             order by r.occurredAt desc
            """)
    Page<RemoteDrivingRecord> search(@Param("vin") String vin,
                                     @Param("closed") Boolean closed,
                                     @Param("from") Instant from,
                                     @Param("to") Instant to,
                                     Pageable pageable);

    Optional<RemoteDrivingRecord> findFirstByVinAndClosedFalseOrderByOccurredAtDesc(String vin);

    Page<RemoteDrivingRecord> findByClosedFalseOrderByOccurredAtDesc(Pageable pageable);

    long countByOccurredAtAfter(Instant since);

    long countByDrivingTypeAndOccurredAtAfter(String drivingType, Instant since);
}
