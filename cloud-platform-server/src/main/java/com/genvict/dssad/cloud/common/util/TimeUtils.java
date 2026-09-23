package com.genvict.dssad.cloud.common.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * 时间工具。
 *
 * <p>严格遵守接口文档 3.1 基础数据要求第 2 条：
 * <b>时间戳采用东八区 UTC 时间，13 位带毫秒长整型 Unix 时间戳</b>。
 * 因此：
 * <ul>
 *   <li>报文中的 {@code timestamp} 一律用 {@link #nowMillis()} 生成，落库为 {@link Instant}（UTC 存储）；</li>
 *   <li>对外展示与「历史视频」接口的 {@code yyyy-MM-dd HH:mm:ss} 文本一律按
 *       {@link #ZONE_CN}（Asia/Shanghai，UTC+8）格式化。</li>
 * </ul>
 */
public final class TimeUtils {

    /** 东八区时区。 */
    public static final ZoneId ZONE_CN = ZoneId.of("Asia/Shanghai");

    /** 文档约定的日期时间文本格式，用于历史视频查询入参。 */
    public static final String PATTERN_DATETIME = "yyyy-MM-dd HH:mm:ss";

    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern(PATTERN_DATETIME);

    private TimeUtils() {
    }

    /** 当前 13 位毫秒时间戳。 */
    public static long nowMillis() {
        return Instant.now().toEpochMilli();
    }

    /** 毫秒时间戳 → {@link LocalDateTime}（东八区）。 */
    public static LocalDateTime toLocalDateTime(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZONE_CN);
    }

    /** {@link LocalDateTime}（东八区语义）→ 毫秒时间戳。 */
    public static long toEpochMillis(LocalDateTime dateTime) {
        return dateTime.atZone(ZONE_CN).toInstant().toEpochMilli();
    }

    /** 毫秒时间戳 → {@link Instant}。 */
    public static Instant toInstant(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis);
    }

    /** {@link Instant} → 毫秒时间戳，null 安全。 */
    public static long toEpochMillis(Instant instant) {
        return instant == null ? 0L : instant.toEpochMilli();
    }

    /** {@link Date} → 毫秒时间戳，null 安全。 */
    public static long toEpochMillis(Date date) {
        return date == null ? 0L : date.getTime();
    }

    /** 毫秒时间戳 → 东八区 {@code yyyy-MM-dd HH:mm:ss}。 */
    public static String format(long epochMillis) {
        return format(epochMillis, DATETIME_FORMATTER);
    }

    /** 毫秒时间戳 → 东八区自定义格式。 */
    public static String format(long epochMillis, DateTimeFormatter formatter) {
        return toLocalDateTime(epochMillis).format(formatter);
    }

    /** {@link Instant} → 东八区 {@code yyyy-MM-dd HH:mm:ss}，null 安全。 */
    public static String format(Instant instant) {
        return instant == null ? null : format(instant.toEpochMilli());
    }

    /**
     * 解析东八区 {@code yyyy-MM-dd HH:mm:ss} 文本为毫秒时间戳。
     *
     * @throws IllegalArgumentException 格式不合法
     */
    public static long parseDateTime(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("时间文本不能为空");
        }
        return toEpochMillis(LocalDateTime.parse(text.trim(), DATETIME_FORMATTER));
    }

    /**
     * 解析历史视频接口的时间入参，兼容 {@code yyyy-MM-dd HH:mm:ss} 与
     * {@code yyyy-MM-dd'T'HH:mm:ss}（ISO-8601）两种写法。
     */
    public static long parseFlexible(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("时间文本不能为空");
        }
        String normalized = text.trim();
        if (normalized.contains("T")) {
            normalized = normalized.replace('T', ' ');
        }
        if (normalized.length() == 10) {
            normalized = normalized + " 00:00:00";
        }
        return parseDateTime(normalized.length() > 19 ? normalized.substring(0, 19) : normalized);
    }
}
