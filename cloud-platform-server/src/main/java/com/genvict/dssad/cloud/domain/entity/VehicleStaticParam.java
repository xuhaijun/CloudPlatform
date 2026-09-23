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
 * 车辆准静态参数上报流水（{@code inh}，文档 5.1.4.2.1）。
 *
 * <p>车端在每次 MQTT 连接/重连完成后上报一次，因此本表是<b>低频表</b>（每车每天几次），
 * 保留全量历史用于追溯「软件版本何时升级」。
 *
 * <p>主键由 {@link com.genvict.dssad.cloud.common.util.SnowflakeIdGenerator} 应用侧分配
 * （{@code ASSIGNED} 策略），理由见雪花生成器类注释。
 */
@Getter
@Setter
@Entity
@Table(name = "t_vehicle_static_param", indexes = {
        @Index(name = "idx_inh_vin_time", columnList = "vin, reported_at"),
        @Index(name = "idx_inh_msg_id", columnList = "msg_id")
})
public class VehicleStaticParam extends BaseEntity {

    /** 主键（雪花 ID）。 */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** 车辆 VIN。 */
    @Column(name = "vin", length = 32, nullable = false)
    private String vin;

    /** 车端消息编号（UUID），用于与 ACK 配对去重。 */
    @Column(name = "msg_id", length = 64)
    private String msgId;

    /** 车端上报时间（由报文 timestamp 转换，UTC）。 */
    @Column(name = "reported_at", nullable = false)
    private Instant reportedAt;

    /** 自动驾驶硬件版本。 */
    @Column(name = "hw_version", length = 64)
    private String hwVersion;

    /** 自动驾驶系统软件版本号。 */
    @Column(name = "ad_version", length = 64)
    private String adVersion;

    /** 蜂窝通讯类型。 */
    @Column(name = "com_type", length = 8)
    private String comType;

    /** 时间同步方式。 */
    @Column(name = "time_sync", length = 8)
    private String timeSync;
}
