package com.genvict.dssad.cloud.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 车辆轨迹点（{@code low-base}，文档 5.1.4.3.2，<b>1s 一次</b>）。
 *
 * <p>本表是平台写入压力最大的表：1000 台车 ≈ 8640 万行/天。设计要点：
 * <ol>
 *   <li><b>极窄行</b>：仅保留 8 个数值列 + 复合索引，不含任何长字符串，保证单页容纳更多行；</li>
 *   <li><b>时间列为 {@code BIGINT} 毫秒</b>：与协议一致，且范围查询（{@code BETWEEN}）比
 *       {@code DATETIME} 更快、无需时区转换；</li>
 *   <li><b>应用侧分配主键</b>：让 Hibernate 能走 JDBC 批量插入（IDENTITY 策略会禁用批处理），
 *       配合 {@code hibernate.jdbc.batch_size=500} 与 {@code rewriteBatchedStatements=true}
 *       可将写入吞吐提升约 5~10 倍；</li>
 *   <li><b>按天分区</b>：生产环境 MySQL DDL 见 {@code db/schema-mysql.sql}，
 *       按 {@code ts} 做 RANGE 分区，历史分区可直接 DROP，避免大表 DELETE 造成锁与碎片。</li>
 * </ol>
 *
 * <p>注意：本表<b>不继承</b> {@link BaseEntity}。追加型大表如果每行再冗余
 * {@code created_at}/{@code updated_at}，会产生 16 字节/行的纯浪费（8640 万行/天 ≈ 1.4GB/天），
 * 且业务时间已由 {@code ts} 精确表达。
 */
@Getter
@Setter
@Entity
@Table(name = "t_vehicle_track_point", indexes = {
        @Index(name = "idx_track_vin_ts", columnList = "vin, ts"),
        @Index(name = "idx_track_ts", columnList = "ts")
})
public class VehicleTrackPoint {

    /** 主键（雪花 ID，应用侧分配）。 */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** 车辆 VIN。 */
    @Column(name = "vin", length = 32, nullable = false)
    private String vin;

    /** 采样时间（13 位毫秒时间戳，东八区 UTC 时间）。 */
    @Column(name = "ts", nullable = false)
    private Long ts;

    /** 经度（GCJ-02，东经为正）。 */
    @Column(name = "longitude")
    private Double longitude;

    /** 纬度（GCJ-02，北纬为正）。 */
    @Column(name = "latitude")
    private Double latitude;

    /** 高程 m。 */
    @Column(name = "altitude")
    private Double altitude;

    /** 速度 km/h。 */
    @Column(name = "speed")
    private Double speed;

    /** 加速度（单位 0.01 m/s²）。 */
    @Column(name = "acc_speed")
    private Double accSpeed;

    /** 驾驶模式：0 自动驾驶 / 1 人工驾驶 / 2 远程接管。 */
    @Column(name = "driving_mode")
    private Integer drivingMode;

    /** 航向角（正北 0 度，顺时针，精度 0.01 度）。 */
    @Column(name = "heading")
    private Double heading;
}
