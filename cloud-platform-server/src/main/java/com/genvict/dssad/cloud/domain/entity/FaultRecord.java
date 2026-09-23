package com.genvict.dssad.cloud.domain.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次故障上报（{@code error-report}，文档 5.1.4.4.2）。
 *
 * <p>报文一次可携带 N 条故障（{@code errorData} 数组），故拆成「主表 + 明细表」：
 * 主表承载上报级信息（车辆、时间、位置、故障个数），明细表承载每条故障码。
 * 这样既能按「上报事件」回溯，又能按 {@code error_code} 做聚合统计
 * （如「近 30 天胎压报警 Top10 车辆」），避免把明细塞进 JSON 导致无法索引。
 */
@Getter
@Setter
@Entity
@Table(name = "t_fault_record", indexes = {
        @Index(name = "idx_fault_vin_time", columnList = "vin, reported_at"),
        @Index(name = "idx_fault_severity", columnList = "max_severity, reported_at"),
        // 单列时间索引：故障中心的「近 N 天统计」全部只按 reported_at 过滤
        // （countByReportedAtAfter、countByMaxSeverityGreaterThanEqualAndReportedAtAfter，
        //  以及 FaultItemRepository 的 topErrorCodes/topFaultVehicles 对父表的 JOIN 过滤）。
        // 没有它时这些聚合会走聚簇索引全扫 —— 明细表已有 error_code 索引，父表反而成了瓶颈。
        @Index(name = "idx_fault_time", columnList = "reported_at")
})
public class FaultRecord extends BaseEntity {

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 车辆 VIN。 */
    @Column(name = "vin", length = 32, nullable = false)
    private String vin;

    /** 上报消息编号。 */
    @Column(name = "msg_id", length = 64)
    private String msgId;

    /** 上报时间（报文 timestamp）。 */
    @Column(name = "reported_at", nullable = false)
    private Instant reportedAt;

    /** 故障个数（报文 errorNum）。 */
    @Column(name = "error_num")
    private Integer errorNum;

    /**
     * 本次上报中最严重的等级（0~7）。
     *
     * <p>冗余字段：故障中心列表需要按「严重程度」排序/过滤，若放在明细表会导致
     * 主表查询必须 JOIN 聚合，故在写入时算好。取值越大越严重。
     */
    @Column(name = "max_severity")
    private Integer maxSeverity;

    /** 上报时的纬度。 */
    @Column(name = "latitude")
    private Double latitude;

    /** 上报时的经度。 */
    @Column(name = "longitude")
    private Double longitude;

    /**
     * 故障明细。
     *
     * <p><b>为什么 {@code @JsonIgnore}</b>：本实体被列表接口直接返回，而列表视图只需要
     * 「故障码 / 最高等级 / 时间」，不需要明细。若参与序列化会有两个后果：
     * <ol>
     *   <li>在 {@code spring.jpa.open-in-view=false} 下，Jackson 序列化发生在事务之外，
     *       懒加载集合会抛 {@code LazyInitializationException}，把整个列表接口变成 9999；
     *   <li>即使能加载，一页 20 条 × 每条若干明细会显著放大响应体，纯属浪费。</li>
     * </ol>
     * 明细改由故障详情接口（{@code GET /api/v1/events/faults/{id}}）单独返回，
     * 与事故详情「主记录 + 关联清单」的结构保持一致。
     * 该约束由 {@code PlatformEndToEndTest.faultItemsArePersistedAndTranslated} 守护。
     */
    @JsonIgnore
    @OneToMany(mappedBy = "faultRecord", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderBy("errorCode asc")
    private List<FaultItem> items = new ArrayList<>();

    /** 建立双向关联，保证外键正确。 */
    public void addItem(FaultItem item) {
        item.setFaultRecord(this);
        this.items.add(item);
    }
}
