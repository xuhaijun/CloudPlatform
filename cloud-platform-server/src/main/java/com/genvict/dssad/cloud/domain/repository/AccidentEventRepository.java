package com.genvict.dssad.cloud.domain.repository;

import com.genvict.dssad.cloud.domain.entity.AccidentEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 事故事件仓储。 */
public interface AccidentEventRepository extends JpaRepository<AccidentEvent, Long> {

    Optional<AccidentEvent> findByEventId(String eventId);

    /** 通过「媒体请求消息编号」反查事件：车端回复 {@code accident-media-resp} 时以其 ackMsgId 配对。 */
    Optional<AccidentEvent> findFirstByMediaRequestMsgId(String mediaRequestMsgId);

    long countByMediaStatus(AccidentEvent.MediaStatus mediaStatus);

    boolean existsByEventId(String eventId);

    Page<AccidentEvent> findByVinOrderByOccurredAtDesc(String vin, Pageable pageable);

    Page<AccidentEvent> findByOccurredAtBetweenOrderByOccurredAtDesc(Instant from, Instant to, Pageable pageable);

    Page<AccidentEvent> findByMediaStatusOrderByOccurredAtDesc(String mediaStatus, Pageable pageable);

    long countByOccurredAtAfter(Instant since);

    /**
     * 待跟进事件（已请求媒体但未归档），用于运维巡检与告警。
     *
     * <p>状态集合通过参数传入而非在 JPQL 中写枚举字面量：Hibernate 在启动时会解析并校验
     * 全部 {@code @Query}，枚举字面量一旦写法有偏差会直接导致<b>应用启动失败</b>；
     * 参数化同时让调用方可以按需调整状态集合。
     */
    /**
     * 组合条件分页查询（筛选条件之间是「与」关系）。
     *
     * <p>替代原先「按 vin 或按 mediaStatus 二选一」的写法，避免同时传两个条件时
     * 其中一个被静默忽略（见 {@code FaultRecordRepository#search} 的同款说明）。
     */
    @Query("""
            select a from AccidentEvent a
             where (:vin is null or a.vin = :vin)
               and (:mediaStatus is null or a.mediaStatus = :mediaStatus)
               and (:from is null or a.occurredAt >= :from)
               and (:to is null or a.occurredAt <= :to)
             order by a.occurredAt desc
            """)
    Page<AccidentEvent> search(@Param("vin") String vin,
                               @Param("mediaStatus") AccidentEvent.MediaStatus mediaStatus,
                               @Param("from") Instant from,
                               @Param("to") Instant to,
                               Pageable pageable);

    @Query("""
            select a from AccidentEvent a
            where a.mediaStatus in :statuses and a.occurredAt < :before
            order by a.occurredAt asc
            """)
    List<AccidentEvent> findUnarchivedBefore(@Param("statuses") Collection<AccidentEvent.MediaStatus> statuses,
                                             @Param("before") Instant before,
                                             Pageable pageable);
}
