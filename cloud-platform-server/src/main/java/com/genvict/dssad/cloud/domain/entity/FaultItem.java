package com.genvict.dssad.cloud.domain.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 故障明细（{@code errorData} 数组元素）。
 *
 * <p>故障码取值见接口文档附录 A.1（如 4352 电池系统故障、4864 GNSS 信号故障、
 * 5381 黑匣子（EDR/DSSAD）存储异常），共 6 大类 28 个码。
 * 平台侧同时维护 {@code FaultCodeDictionary} 做码值到中英文描述的翻译与分类归集，
 * 避免前端硬编码码表。
 */
@Getter
@Setter
@Entity
@Table(name = "t_fault_item", indexes = {
        @Index(name = "idx_fault_item_code", columnList = "error_code, error_timestamp"),
        @Index(name = "idx_fault_item_record", columnList = "fault_record_id")
})
public class FaultItem extends BaseEntity {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * 所属上报记录。
     *
     * <p>对 JSON 序列化隐藏：明细经由故障详情接口以「主记录 + items 数组」的结构返回，
     * 若把父引用一并输出，Jackson 会沿着 {@code item -> record -> items -> item} 形成环，
     * 轻则响应体膨胀数倍，重则栈溢出；而且父记录已在同一响应的另一字段中给出，信息重复。
     */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fault_record_id", nullable = false)
    private FaultRecord faultRecord;

    /** 车辆 VIN（冗余，便于不 JOIN 直接按车统计）。 */
    @Column(name = "vin", length = 32, nullable = false)
    private String vin;

    /** 故障码（附录 A.1）。 */
    @Column(name = "error_code", length = 16, nullable = false)
    private String errorCode;

    /** 故障发生时间（毫秒时间戳）。 */
    @Column(name = "error_timestamp")
    private Long errorTimestamp;

    /** 严重等级 0~7：0~1 无影响 / 2~3 可恢复 / 4~5 需人工处理 / 6~7 严重不可恢复。 */
    @Column(name = "error_severity", length = 8)
    private String errorSeverity;

    /** 该故障类别的累计上报次数（车端自计量，数值突然跳变往往意味着反复故障）。 */
    @Column(name = "msg_count")
    private Integer msgCount;

    /** 故障原因（车端原文，如「电池系统故障」）。 */
    @Column(name = "error_reason", length = 255)
    private String errorReason;
}
