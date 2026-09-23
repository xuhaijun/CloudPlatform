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
 * 媒体资产（视频文件）。
 *
 * <p>三类场景共表：
 * <ul>
 *   <li>{@link Kind#ACCIDENT}：车端因事故请求上传的「前 15s + 后 5s」四方向视频（文档 3.3）；</li>
 *   <li>{@link Kind#HISTORY}：企业侧归档的历史行车记录（文档 3.2 要求车端至少存 10 天）；</li>
 *   <li>{@link Kind#LIVE}：实时拉流地址（不落文件，仅记录地址与可用性，便于前端统一取址）。</li>
 * </ul>
 *
 * <p>存储位置：{@code storagePath} 为对象存储/本地磁盘的相对路径，{@code url} 为对外可访问地址。
 * 二者分开是因为「对外地址」会随网关/域名变化（如开发环境 {@code 127.0.0.1}、
 * 生产环境 CDN 域名），不应与物理存储路径耦合。
 */
@Getter
@Setter
@Entity
@Table(name = "t_media_asset", indexes = {
        @Index(name = "idx_media_event_dir", columnList = "event_id, camera_direction"),
        @Index(name = "idx_media_vin_time", columnList = "vin, start_time"),
        @Index(name = "idx_media_status", columnList = "status")
})
public class MediaAsset extends BaseEntity {

    /** 媒体来源类型。 */
    public enum Kind {
        /** 事故取证视频。 */
        ACCIDENT,
        /** 历史行车记录。 */
        HISTORY,
        /** 实时流。 */
        LIVE
    }

    /** 归档状态。 */
    public enum Status {
        /** 已登记但文件尚未到位。 */
        PENDING,
        /** 可用。 */
        AVAILABLE,
        /** 文件缺失/损坏。 */
        MISSING
    }

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 车辆 VIN。 */
    @Column(name = "vin", length = 32, nullable = false)
    private String vin;

    /** 关联事故事件 ID（历史/实时视频为空）。 */
    @Column(name = "event_id", length = 64)
    private String eventId;

    /** 摄像头方向：1 前向 / 2 右向 / 3 后向 / 4 左向。 */
    @Column(name = "camera_direction", length = 4)
    private String cameraDirection;

    /** 视频流/文件类型：m3u8 / flv / mp4（与协议 type 字段对应）。 */
    @Column(name = "media_type", length = 8)
    private String mediaType;

    /** 对外可访问地址（拉流地址或下载地址）。 */
    @Column(name = "url", length = 512)
    private String url;

    /** 存储相对路径（对象存储 key 或本地相对路径）。 */
    @Column(name = "storage_path", length = 512)
    private String storagePath;

    /** 视频起始时间（历史视频用）。 */
    @Column(name = "start_time")
    private Instant startTime;

    /** 视频结束时间。 */
    @Column(name = "end_time")
    private Instant endTime;

    /** 文件大小（字节）。 */
    @Column(name = "file_size")
    private Long fileSize;

    /** 来源类型。 */
    @Column(name = "kind", length = 16, nullable = false)
    private String kind;

    /** 归档状态。 */
    @Column(name = "status", length = 16, nullable = false)
    private String status = Status.PENDING.name();

    /** 上传方（车端上传填 VIN；平台构建填 SYSTEM）。 */
    @Column(name = "uploaded_by", length = 32)
    private String uploadedBy;

    /** 媒体请求的消息编号（事故场景，用于与 accident-media-resp 对账）。 */
    @Column(name = "request_msg_id", length = 64)
    private String requestMsgId;
}
