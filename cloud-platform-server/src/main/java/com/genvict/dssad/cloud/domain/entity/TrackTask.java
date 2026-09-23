package com.genvict.dssad.cloud.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 运营任务（{@code task}，文档 6.1.4.2.2）。
 *
 * <p>企业云端向无人车下发/结束任务时同步上报监管平台。平台侧把 taskAction=开始/结束
 * 配对成<b>一条任务生命周期记录</b>：
 * <ul>
 *   <li>收到 {@code taskAction=1}（开始）时插入新记录（状态 {@link TaskStatus#RUNNING}）；</li>
 *   <li>收到 {@code taskAction=2}（结束）时按 {@code taskId} 更新同一条记录为
 *       {@link TaskStatus#FINISHED} 并记录结束时间。</li>
 * </ul>
 * 配对键是 {@code taskId} 而非自增主键，因此对其建唯一索引保证幂等（车端/企业云端重发同一任务不会产生两条）。
 */
@Getter
@Setter
@Entity
@Table(name = "t_track_task", indexes = {
        @Index(name = "uk_task_id", columnList = "task_id", unique = true),
        @Index(name = "idx_task_vin_status", columnList = "vin, status"),
        @Index(name = "idx_task_started_at", columnList = "started_at"),
        // 「按状态看任务」是运营页的默认视图（进行中 / 已结束 / 孤儿三条），
        // 而 idx_task_vin_status 的前导列是 vin，无法服务只有 status 的过滤与排序
        // （findByStatusOrderByStartedAtDesc / countByStatus），
        // 缺少本索引时运营页每次刷新都会全表扫描 + filesort。
        @Index(name = "idx_task_status_time", columnList = "status, started_at")
})
public class TrackTask extends BaseEntity {

    /** 任务状态。 */
    public enum TaskStatus {
        /** 进行中。 */
        RUNNING,
        /** 已结束。 */
        FINISHED,
        /** 只收到结束报文、未找到开始记录（数据缺失，需人工核查）。 */
        ORPHAN_FINISH
    }

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 任务 ID（企业云端生成，如 TASK_20251105_001）。 */
    @Column(name = "task_id", length = 64, nullable = false)
    private String taskId;

    /** 企业 ID。 */
    @Column(name = "enterprise_id", length = 32)
    private String enterpriseId;

    /** 车辆 VIN。 */
    @Column(name = "vin", length = 32, nullable = false)
    private String vin;

    /** 任务起点描述。 */
    @Column(name = "start_position", length = 255)
    private String startPosition;

    /** 起点纬度。 */
    @Column(name = "start_latitude")
    private Double startLatitude;

    /** 起点经度。 */
    @Column(name = "start_longitude")
    private Double startLongitude;

    /** 任务终点描述。 */
    @Column(name = "end_position", length = 255)
    private String endPosition;

    /** 终点纬度。 */
    @Column(name = "end_latitude")
    private Double endLatitude;

    /** 终点经度。 */
    @Column(name = "end_longitude")
    private Double endLongitude;

    /** 任务开始时间。 */
    @Column(name = "started_at")
    private Instant startedAt;

    /** 任务结束时间。 */
    @Column(name = "finished_at")
    private Instant finishedAt;

    /** 任务状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private TaskStatus status = TaskStatus.RUNNING;

    /** 最近一次任务的报文编号（追溯用）。 */
    @Column(name = "last_msg_id", length = 64)
    private String lastMsgId;

    /** 任务执行时长（秒），结束时计算并落库，避免列表页每次实时计算。 */
    @Column(name = "duration_seconds")
    private Long durationSeconds;
}
