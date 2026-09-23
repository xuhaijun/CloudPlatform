package com.genvict.dssad.cloud.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 事故事件（{@code accident}，文档 5.1.4.4.1）。
 *
 * <p>业务闭环：车端上报事故 → 平台落库并把状态置为 {@link MediaStatus#REPORTED} →
 * 平台下发 {@code accident-media/down} 请求四方向视频 → 车端 MQTT 应答（{@code accident-media-resp}）
 * → 车端通过 HTTP 上传视频 → 状态推进到 {@link MediaStatus#MEDIA_ARCHIVED}。
 *
 * <p>{@code eventId} 由车端生成，是本事件与视频文件的<b>唯一关联键</b>，
 * 因此建立唯一索引，保证同一事故重复上报时只落一条（幂等）。
 */
@Getter
@Setter
@Entity
@Table(name = "t_accident_event", indexes = {
        @Index(name = "uk_accident_event_id", columnList = "event_id", unique = true),
        @Index(name = "idx_accident_vin_time", columnList = "vin, occurred_at"),
        @Index(name = "idx_accident_status", columnList = "media_status, occurred_at")
})
public class AccidentEvent extends BaseEntity {

    /** 媒体取证状态。 */
    public enum MediaStatus {
        /** 仅收到结构化上报，尚未请求媒体。 */
        REPORTED,
        /** 已下发媒体请求，等待车端上传。 */
        MEDIA_REQUESTED,
        /** 车端已受理（收到 accident-media-resp 且 res=1）。 */
        MEDIA_ACCEPTED,
        /** 视频已归档完成。 */
        MEDIA_ARCHIVED,
        /** 媒体请求失败或超时（需人工介入，文档要求车端本地至少存 10 天）。 */
        MEDIA_FAILED
    }

    /** 主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 车端生成的事故 ID。 */
    @Column(name = "event_id", length = 64, nullable = false)
    private String eventId;

    /** 车辆 VIN。 */
    @Column(name = "vin", length = 32, nullable = false)
    private String vin;

    /** 企业 ID。 */
    @Column(name = "enterprise_id", length = 32)
    private String enterpriseId;

    /** 车端上报用的消息编号。 */
    @Column(name = "msg_id", length = 64)
    private String msgId;

    /** 事故发生时间（报文 timestamp）。 */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** 事故描述（车端原文）。 */
    @Lob
    @Column(name = "accident_desc", length = 4000)
    private String accidentDesc;

    /** 事发纬度（报文 currentPoint[0]）。 */
    @Column(name = "latitude")
    private Double latitude;

    /** 事发经度（报文 currentPoint[1]）。 */
    @Column(name = "longitude")
    private Double longitude;

    /** 媒体取证状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "media_status", length = 24, nullable = false)
    private MediaStatus mediaStatus = MediaStatus.REPORTED;

    /** 发起媒体请求的时间。 */
    @Column(name = "media_requested_at")
    private Instant mediaRequestedAt;

    /** 已归档视频数量（覆盖前/右/后/左 4 个方向）。 */
    @Column(name = "media_count", nullable = false)
    private Integer mediaCount = 0;

    /**
     * 本次媒体请求要求的方向数量（用于判断「是否已收齐」）。
     *
     * <p>必须落库而不是写死 4：监管平台可能只请求 1~2 个方向（例如仅前向），
     * 若按固定 4 判断，事故事件将永远停在 MEDIA_ACCEPTED，前端一直显示「取证中」。
     */
    @Column(name = "media_expected_count", nullable = false)
    private Integer mediaExpectedCount = 0;

    /** 媒体请求下发的消息编号（用于与 accident-media-resp 配对）。 */
    @Column(name = "media_request_msg_id", length = 64)
    private String mediaRequestMsgId;
}
