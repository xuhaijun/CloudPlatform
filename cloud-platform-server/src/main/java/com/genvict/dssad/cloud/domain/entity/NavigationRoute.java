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

/**
 * 车端导航路径（{@code groute}，文档 6.1.4.2.3）。
 *
 * <p>报文携带的是「起点 + 途经点 + 终点 + 全部路径点」，点数可达数千。
 * 存储策略：路径点整体以 JSON 文本保存，并冗余起点/终点/点数三个字段供列表与筛选使用。
 *
 * <p>为什么不拆成「路径点明细表」？导航路径是<b>一次性整体消费</b>的数据
 * （要么整条画出来，要么不用），没有按单个路径点过滤/聚合的场景，
 * 拆表只会带来几千倍的存储放大与写入开销。
 */
@Getter
@Setter
@Entity
@Table(name = "t_navigation_route", indexes = {
        @Index(name = "idx_route_vin_time", columnList = "vin, reported_at"),
        @Index(name = "idx_route_msg_id", columnList = "msg_id")
})
public class NavigationRoute extends BaseEntity {

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

    /** 报文编号。 */
    @Column(name = "msg_id", length = 64)
    private String msgId;

    /** 上报时间。 */
    @Column(name = "reported_at", nullable = false)
    private Instant reportedAt;

    /**
     * 全部路径点 JSON：{@code [[lat,lng],...]}，首元素为起点、末元素为终点。
     *
     * <p>显式 length=1MB：一次跨城任务路径可达数万个点，若用 {@code tinytext}（255 字节）
     * 会直接截断，轨迹回放将只剩起点。1MB 对应 Hibernate 选取的 {@code mediumtext}。
     */
    @Lob
    @Column(name = "path_points", length = 1048576)
    private String pathPoints;

    /** 路径点数量。 */
    @Column(name = "point_count")
    private Integer pointCount;

    /** 起点纬度。 */
    @Column(name = "start_latitude")
    private Double startLatitude;

    /** 起点经度。 */
    @Column(name = "start_longitude")
    private Double startLongitude;

    /** 终点纬度。 */
    @Column(name = "end_latitude")
    private Double endLatitude;

    /** 终点经度。 */
    @Column(name = "end_longitude")
    private Double endLongitude;

    /** 上报时的当前位置纬度。 */
    @Column(name = "current_latitude")
    private Double currentLatitude;

    /** 上报时的当前位置经度。 */
    @Column(name = "current_longitude")
    private Double currentLongitude;
}
