package com.genvict.dssad.cloud.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 交通阻断设施 / 地图增强信息（{@code mapehn}，文档 6.1.5.2.1、6.2.2.2.1）。
 *
 * <p>两个来源统一落本表，用 {@code source} 区分：
 * <ul>
 *   <li>{@link Source#PUSH}：监管平台通过 MQTT {@code mapehn/down} 主动推送；</li>
 *   <li>{@link Source#PULL}：平台定时通过 HTTP {@code /event/mapehn} 拉取「今日」阻断点。</li>
 * </ul>
 *
 * <p>幂等键为 {@code (barrierId, effectiveDate)}：同一阻断设施在同一天重复推送/拉取只更新不新增。
 * 多边形坐标与时间段用 JSON 存 TEXT（查询场景是「按区域取出后整片渲染」，
 * 不需要按顶点做关系查询；真正的空间检索应交给 PostGIS/ES 而非 MySQL）。
 */
@Getter
@Setter
@Entity
@Table(name = "t_map_barrier", indexes = {
        @Index(name = "uk_barrier_id_date", columnList = "barrier_id, effective_date", unique = true),
        @Index(name = "idx_barrier_area_date", columnList = "area_code, effective_date"),
        @Index(name = "idx_barrier_type", columnList = "barrier_type"),
        // 「今日阻断点」是整个模块最高频的查询（前端首屏 + 每日拉取对账），
        // 但 uk_barrier_id_date 的前导列是 barrier_id、idx_barrier_area_date 的前导列是 area_code，
        // 两者都无法服务「只按日期过滤」的条件（findByEffectiveDate / countByEffectiveDate），
        // 因此必须有日期单列索引，否则每天都要全表扫描一遍。
        @Index(name = "idx_barrier_date", columnList = "effective_date")
})
public class MapBarrier extends BaseEntity {

    /** 数据来源。 */
    public enum Source {
        /** 平台主动推送（MQTT）。 */
        PUSH,
        /** 平台定时拉取（HTTP）。 */
        PULL
    }

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 企业 ID。 */
    @Column(name = "enterprise_id", length = 32)
    private String enterpriseId;

    /** 区域编码。 */
    @Column(name = "area_code", length = 64)
    private String areaCode;

    /** 交通阻断编号（平台分配）。 */
    @Column(name = "barrier_id", length = 64, nullable = false)
    private String barrierId;

    /** 交通阻断类型：20 禁行区域；21 限速区域。 */
    @Column(name = "barrier_type", length = 8, nullable = false)
    private String barrierType;

    /** 交通阻断名称，如「学校区域」。 */
    @Column(name = "barrier_name", length = 128)
    private String barrierName;

    /** 阻断区域多边形顶点 JSON：{@code [[lat,lng],...]}，首尾同点形成闭环。 */
    @Lob
    @Column(name = "barrier_polygon", length = 65535)
    private String barrierPolygon;

    /** 时间段类型：1 工作日 / 2 节假日 / 3 每日 / 4 周六周日。 */
    @Column(name = "date_type", length = 8)
    private String dateType;

    /** 生效时间段 JSON：{@code [["7:00","9:00"],["17:00","19:30"]]}。 */
    @Lob
    @Column(name = "time_range", length = 1024)
    private String timeRange;

    /** 限速值（barrierType=21 时有效）。 */
    @Column(name = "speed_limit")
    private Double speedLimit;

    /** 生效日期（「今日地图增强信息」按天拉取，故以日期作为幂等维度之一）。 */
    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    /** 数据来源。 */
    @Column(name = "source", length = 8)
    private String source;

    /** 平台侧消息编号（推送时）。 */
    @Column(name = "msg_id", length = 64)
    private String msgId;

    /** 平台侧下发时间。 */
    @Column(name = "received_at")
    private Instant receivedAt;

    /** 是否已回复 ACK（推送场景下必须回复 mapehn-resp，此项用于对账未回复的推送）。 */
    @Column(name = "acked", nullable = false)
    private Boolean acked = Boolean.FALSE;
}
