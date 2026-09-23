package com.genvict.dssad.cloud.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 车辆低频运行状态流水（{@code low-state}，文档 5.1.4.3.1，60s 一次）。
 *
 * <p>写入策略：本表按<b>流水</b>落库（保留全量供取证），同时把最新值写入 Redis
 * 供车辆列表/大屏实时读取，避免列表页对每台车都做一次 {@code ORDER BY reported_at DESC LIMIT 1}。
 *
 * <p>胎压为不定长数组（车轮数 N 可变），用 JSON 字符串存储并保证顺序
 * （协议规定：从车头开始从左到右）。不做拆表，因为查询场景是「整组展示」，无需按单个胎压过滤。
 *
 * <p>与轨迹表同理，本表为追加型流水，不继承 {@link BaseEntity}（业务时间即 {@code reported_at}）。
 */
@Getter
@Setter
@Entity
@Table(name = "t_vehicle_state_snapshot", indexes = {
        @Index(name = "idx_state_vin_time", columnList = "vin, reported_at")
})
public class VehicleStateSnapshot {

    /** 主键（雪花 ID）。 */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** 车辆 VIN。 */
    @Column(name = "vin", length = 32, nullable = false)
    private String vin;

    /** 车端消息编号。 */
    @Column(name = "msg_id", length = 64)
    private String msgId;

    /** 车端上报时间（UTC）。 */
    @Column(name = "reported_at", nullable = false)
    private Instant reportedAt;

    /** 定位状态 0~13（4=固定解，13=RTK+IMU+视觉组合有效）。 */
    @Column(name = "gnss_status", length = 8)
    private String gnssStatus;

    /** 实时总质量 kg，0 表示未知。 */
    @Column(name = "vehicle_total_weight")
    private Double vehicleTotalWeight;

    /** 胎压数组 JSON，单位 kPa。 */
    @Column(name = "tire_pressure", length = 512)
    private String tirePressure;

    /** 预计续航 km。 */
    @Column(name = "endurance")
    private Double endurance;

    /** 里程表读数 km。 */
    @Column(name = "mileage")
    private Double mileage;

    /** 电池剩余电量 %。 */
    @Column(name = "soc")
    private Double soc;

    /** 电池容量（度）。 */
    @Column(name = "battery_capacity")
    private Double batteryCapacity;

    /** 电池温度上报原始值（实际摄氏度 = 本值 - 100）。落库保留协议原文，便于与车端报文逐字对账。 */
    @Column(name = "temperature")
    private Integer temperature;

    /**
     * 电池温度实际摄氏度（{@link #temperature} 减 100）。
     *
     * <p>派生属性，不落库；存在的意义是让历史状态接口的响应自带真值，
     * 消费方无需了解协议偏移量。详见 {@code CarUpMessages.LowState#temperatureCelsius()}。
     */
    public Integer getTemperatureCelsius() {
        return temperature == null ? null : temperature - 100;
    }

    /** 充电状态：0 缺省 / 1 未充电 / 3 正在充电 / 4 充电故障。 */
    @Column(name = "charge_state", length = 8)
    private String chargeState;
}
