package com.genvict.dssad.cloud.common.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 雪花算法 ID 生成器（64 位、趋势递增、多实例无冲突）。
 *
 * <p><b>为什么要自己发号而不是用数据库自增？</b>
 * 本平台有两张「大表」：{@code t_vehicle_track_point}（每车 1Hz，1000 台车 ≈ 8640 万行/天）与
 * {@code t_mqtt_message_log}（全量报文留痕）。这类表的写入必须走 <b>JDBC 批量插入</b>，
 * 而 Hibernate 在 {@code GenerationType.IDENTITY} 下为了拿到主键会<b>关闭批量插入</b>
 * （每条 INSERT 都要回读自增主键），吞吐会掉一个数量级。
 * 由应用侧提前分配主键（{@code ASSIGNED}）即可让 Hibernate 正常攒批。
 *
 * <p>结构（标准雪花）：{@code 1 位符号 | 41 位毫秒时间戳 | 5 位机房 | 5 位机器 | 12 位序列}。
 * 机器位默认取本机 IP 末段，避免手工配置出错；可通过
 * {@code dssad.instance.worker-id} 显式覆盖。
 *
 * <p>可靠性：对时钟回拨做了保护 —— 回拨 ≤ 5ms 时自旋等待，超过则抛出异常快速失败，
 * 绝不生成可能重复的 ID（宁可报错也不写脏数据）。
 */
@Component
public class SnowflakeIdGenerator {

    /** 起始纪元：2024-01-01 00:00:00 UTC，决定可用年限（约 69 年）。 */
    private static final long EPOCH = 1704067200000L;

    private static final long WORKER_BITS = 5L;
    private static final long DATACENTER_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;

    private static final long MAX_WORKER_ID = ~(-1L << WORKER_BITS);
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_BITS);
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    private static final long WORKER_SHIFT = SEQUENCE_BITS;
    private static final long DATACENTER_SHIFT = SEQUENCE_BITS + WORKER_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_BITS + DATACENTER_BITS;

    /** 允许的时钟回拨容差（毫秒），超过则快速失败。 */
    private static final long MAX_BACKWARD_TOLERANCE = 5L;

    private final long datacenterId;
    private final long workerId;

    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator(@Value("${dssad.instance.worker-id:-1}") long configuredWorkerId) {
        this.datacenterId = resolveDatacenterId();
        this.workerId = configuredWorkerId >= 0
                ? configuredWorkerId & MAX_WORKER_ID
                : resolveWorkerIdFromHost();
    }

    /** 生成下一个 ID（线程安全）。 */
    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();
        if (timestamp < lastTimestamp) {
            long offset = lastTimestamp - timestamp;
            if (offset > MAX_BACKWARD_TOLERANCE) {
                throw new IllegalStateException(
                        "检测到系统时钟回拨 " + offset + "ms，拒绝生成ID以避免重复，请检查NTP时间同步");
            }
            timestamp = waitUntil(lastTimestamp);
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                // 当前毫秒序列用尽，等待下一毫秒
                timestamp = waitUntil(lastTimestamp + 1);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (datacenterId << DATACENTER_SHIFT)
                | (workerId << WORKER_SHIFT)
                | sequence;
    }

    /** 当前生效的机器位（用于启动日志自检，多实例部署时需保证互不相同）。 */
    public long workerId() {
        return workerId;
    }

    /** 当前生效的机房位。 */
    public long datacenterId() {
        return datacenterId;
    }

    private long waitUntil(long targetMillis) {
        long timestamp = System.currentTimeMillis();
        while (timestamp < targetMillis) {
            Thread.onSpinWait();
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }

    private long resolveDatacenterId() {
        try {
            byte[] address = InetAddress.getLocalHost().getAddress();
            return (address[address.length - 2] & 0x1F) % (MAX_DATACENTER_ID + 1);
        } catch (UnknownHostException e) {
            return 0L;
        }
    }

    private long resolveWorkerIdFromHost() {
        try {
            byte[] address = InetAddress.getLocalHost().getAddress();
            return (address[address.length - 1] & 0x1F) % (MAX_WORKER_ID + 1);
        } catch (UnknownHostException e) {
            return (Thread.currentThread().getId() & MAX_WORKER_ID);
        }
    }
}
