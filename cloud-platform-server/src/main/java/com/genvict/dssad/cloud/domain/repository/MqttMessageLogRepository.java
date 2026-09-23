package com.genvict.dssad.cloud.domain.repository;

import com.genvict.dssad.cloud.domain.entity.MqttMessageLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/** MQTT 报文留痕仓储。 */
public interface MqttMessageLogRepository extends JpaRepository<MqttMessageLog, Long> {

    Page<MqttMessageLog> findBySenderIdOrderByReceivedAtDesc(String senderId, Pageable pageable);

    Page<MqttMessageLog> findByMqttTypeOrderByReceivedAtDesc(String mqttType, Pageable pageable);

    Page<MqttMessageLog> findByProcessStatusOrderByReceivedAtDesc(String processStatus, Pageable pageable);

    List<MqttMessageLog> findByMsgId(String msgId);

    long countByReceivedAtAfter(Instant since);

    long countByProcessStatusAndReceivedAtAfter(String processStatus, Instant since);

    /** 统计时间窗口内平均处理耗时（监控指标）。 */
    @Query("""
            select avg(m.latencyMs) from MqttMessageLog m
            where m.receivedAt >= :since and m.latencyMs is not null
            """)
    Double averageLatencySince(@Param("since") Instant since);

    /** 删除历史报文（保留策略：仅保留 N 天，避免留痕表无限膨胀）。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from MqttMessageLog m where m.receivedAt < :before")
    int deleteBefore(@Param("before") Instant before);
}
