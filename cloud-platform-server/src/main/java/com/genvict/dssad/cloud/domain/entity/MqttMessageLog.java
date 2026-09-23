package com.genvict.dssad.cloud.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * MQTT 全量报文留痕。
 *
 * <p><b>为什么全量留存？</b>本系统服务于《成都市智能网联汽车道路测试、示范应用与示范运营
 * 管理规范实施细则》等监管要求，一旦发生事故或监管核查，必须能自证「某时刻收到了什么、
 * 回复了什么、耗时多久」。因此每个入站/出站报文都登记一条。
 *
 * <p>写入策略（性能关键）：
 * <ul>
 *   <li>应用侧分配雪花主键，走 JDBC 批量插入；</li>
 *   <li>通过 {@code dssad.audit.mode} 支持 {@code full}（全量）/ {@code sampled}（按比例采样）/
 *       {@code event-only}（仅事件类报文）三档，生产环境可根据存储预算选择；</li>
 *   <li>按天分区 + 定期归档到冷存储（见 {@code db/schema-mysql.sql}）。</li>
 * </ul>
 *
 * <p>同样不继承 {@link BaseEntity}：本表是纯追加流水，{@code received_at} 已是业务时间。
 */
@Getter
@Setter
@Entity
@Table(name = "t_mqtt_message_log", indexes = {
        @Index(name = "idx_msg_log_time", columnList = "received_at"),
        @Index(name = "idx_msg_log_vin_time", columnList = "sender_id, received_at"),
        @Index(name = "idx_msg_log_type", columnList = "mqtt_type, received_at"),
        @Index(name = "idx_msg_log_msg_id", columnList = "msg_id")
})
public class MqttMessageLog {

    /** 主键（雪花 ID，应用侧分配）。 */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** 原始 Topic。 */
    @Column(name = "topic", length = 255, nullable = false)
    private String topic;

    /** 方向：UP / DOWN。 */
    @Column(name = "direction", length = 8)
    private String direction;

    /** 消息类型（mqttType 字面量）。 */
    @Column(name = "mqtt_type", length = 32)
    private String mqttType;

    /** 发送方标识：车端为 VIN，企业云端为 enterpriseId。 */
    @Column(name = "sender_id", length = 64)
    private String senderId;

    /** 报文消息编号。 */
    @Column(name = "msg_id", length = 64)
    private String msgId;

    /**
     * 报文体（超长时截断，避免单行过大拖慢查询）。
     *
     * <p><b>必须显式指定 length</b>：{@code @Lob} 不给长度时，Hibernate 会挑 MySQL 最小的
     * 文本类型 {@code tinytext}（仅 255 字节），而文档约定单条 MQTT 报文可达 256KB，
     * 会导致留痕写入失败。给出 256KB 后 Hibernate 选用 {@code mediumtext}（16MB）。
     */
    @Lob
    @Column(name = "payload", length = 262144)
    private String payload;

    /** 报文字节数。 */
    @Column(name = "size_bytes")
    private Integer sizeBytes;

    /** 平台接收/发送时间。 */
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    /** 处理结果：OK / INVALID / UNSUPPORTED / ERROR。 */
    @Column(name = "process_status", length = 16)
    private String processStatus;

    /** 回复的 {@code res} 值。 */
    @Column(name = "ack_code", length = 4)
    private String ackCode;

    /** 失败原因摘要。 */
    @Column(name = "error_message", length = 512)
    private String errorMessage;

    /** 处理耗时（毫秒），用于定位慢 Handler 与端到端时延分析。 */
    @Column(name = "latency_ms")
    private Integer latencyMs;

    /** 报文内时间戳与平台接收时间的差值（毫秒），用于发现车端时钟同步问题。 */
    @Column(name = "clock_skew_ms")
    private Long clockSkewMs;
}
