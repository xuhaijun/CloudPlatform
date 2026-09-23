package com.genvict.dssad.cloud.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 车辆主档（每台接入无人车一行）。
 *
 * <p>VIN 直接作为主键：文档中所有报文都以 VIN 为唯一标识，用业务主键可省掉一次索引查找，
 * 且天然防止重复建档。
 *
 * <p>车辆「准静态参数」（{@code inh} 报文的四个字段）在此<b>冗余最新值</b>，
 * 完整变更历史存放于 {@link VehicleStaticParam}。这样车辆列表页只查一张表，
 * 避免每行都去子表取最新记录（N+1 查询）。
 */
@Getter
@Setter
@Entity
@Table(name = "t_vehicle", indexes = {
        @Index(name = "idx_vehicle_enterprise", columnList = "enterprise_id"),
        @Index(name = "idx_vehicle_online", columnList = "online, last_base_at")
})
public class Vehicle extends BaseEntity {

    /** 车辆 VIN（唯一编号），主键。 */
    @Id
    @Column(name = "vin", length = 32, nullable = false, updatable = false)
    private String vin;

    /** 所属企业 ID（准入平台分配）。 */
    @Column(name = "enterprise_id", length = 32)
    private String enterpriseId;

    /** 无人车类型：{@code low_speed} 功能性无人车 / {@code high_speed} 智能网联车。 */
    @Column(name = "vehicle_type", length = 16)
    private String vehicleType;

    /** 车牌号（企业自有信息，非协议字段）。 */
    @Column(name = "plate_no", length = 16)
    private String plateNo;

    /** 车辆型号（企业自有信息）。 */
    @Column(name = "model", length = 64)
    private String model;

    /** 车载终端/DSSAD 设备序列号（企业自有信息）。 */
    @Column(name = "device_sn", length = 64)
    private String deviceSn;

    /** 自动驾驶硬件版本（来自 inh）。 */
    @Column(name = "hw_version", length = 64)
    private String hwVersion;

    /** 自动驾驶系统软件版本号（来自 inh）。 */
    @Column(name = "ad_version", length = 64)
    private String adVersion;

    /** 蜂窝通讯类型：0 未知 / 1 4G / 2 5G / 3 5GA / 4 6G（来自 inh）。 */
    @Column(name = "com_type", length = 8)
    private String comType;

    /** 时间同步方式：0~5（来自 inh）。 */
    @Column(name = "time_sync", length = 8)
    private String timeSync;

    /** 当前是否在线（由 MQTT 在线状态 + 心跳时间共同判定）。 */
    @Column(name = "online")
    private Boolean online = Boolean.FALSE;

    /** 最近一次上线时间。 */
    @Column(name = "last_online_at")
    private Instant lastOnlineAt;

    /** 最近一次收到准静态参数的时间。 */
    @Column(name = "last_inh_at")
    private Instant lastInhAt;

    /** 最近一次收到低频运行状态（low-state）的时间。 */
    @Column(name = "last_state_at")
    private Instant lastStateAt;

    /** 最近一次收到低频基础信息（low-base）的时间。 */
    @Column(name = "last_base_at")
    private Instant lastBaseAt;

    /** 累计事故数（冗余计数，列表页直接展示）。 */
    @Column(name = "accident_count", nullable = false)
    private Long accidentCount = 0L;

    /** 累计故障上报数（冗余计数）。 */
    @Column(name = "fault_count", nullable = false)
    private Long faultCount = 0L;

    /** 乐观锁版本号，防止并发更新覆盖（如上报处理与人工编辑同档冲突）。 */
    @Version
    @Column(name = "version")
    private Long version;
}
