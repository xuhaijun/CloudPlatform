package com.genvict.dssad.cloud.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 远程驾驶接管记录（{@code remote-driving}，文档 6.1.4.2.1）。
 *
 * <p>记录「发起接管（type=1）→ 结束接管（type=2）」的完整过程。文档要求发起时必传
 * 驾驶员姓名、驾驶证号、台架号（远程驾驶仓编号），这三项属于<b>安全监管取证要素</b>，
 * 因此单独建表并全量留存，不做脱敏覆盖（展示层按权限控制）。
 *
 * <p>与任务不同，协议未提供「接管单号」作为配对键，平台以
 * {@code (vin, 发起时间 → 最近一次结束时间)} 的方式配对：收到 type=2 时，
 * 回填该车最近一条未结束的发起记录。
 */
@Getter
@Setter
@Entity
@Table(name = "t_remote_driving_record", indexes = {
        @Index(name = "idx_remote_vin_time", columnList = "vin, occurred_at"),
        @Index(name = "idx_remote_type", columnList = "driving_type, occurred_at")
})
public class RemoteDrivingRecord extends BaseEntity {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 企业 ID。 */
    @Column(name = "enterprise_id", length = 32)
    private String enterpriseId;

    /** 车辆 VIN。 */
    @Column(name = "vin", length = 32, nullable = false)
    private String vin;

    /** 1 发起接管；2 结束接管（字段名与协议 {@code type} 对应，避免与 Java 关键字联想冲突）。 */
    @Column(name = "driving_type", length = 8, nullable = false)
    private String drivingType;

    /** 驾驶员姓名（发起时必传）。 */
    @Column(name = "driver", length = 64)
    private String driver;

    /** 驾驶证号（发起时必传）。 */
    @Column(name = "driver_card", length = 64)
    private String driverCard;

    /** 台架号 / 远程驾驶舱编号（发起时必传）。 */
    @Column(name = "remote_cockpit_no", length = 64)
    private String remoteCockpitNo;

    /** 接管事件时间（报文 timestamp）。 */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** 报文编号。 */
    @Column(name = "msg_id", length = 64)
    private String msgId;

    /** 结束接管时回填：本次接管持续时长（秒）。 */
    @Column(name = "duration_seconds")
    private Long durationSeconds;

    /** 是否为已结束记录（便于按「进行中的远驾」筛选）。 */
    @Column(name = "closed", nullable = false)
    private Boolean closed = Boolean.FALSE;
}
