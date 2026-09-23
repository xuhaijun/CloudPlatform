package com.genvict.dssad.cloud.common.util;

import java.util.UUID;

/**
 * ID 生成工具。
 *
 * <p>文档对 {@code msgId} / {@code eventId} 的要求是「UUID」，此处统一提供去横线的 32 位小写十六进制形式
 * （更短、URL 与日志友好，且仍满足 UUID v4 随机性要求）。
 */
public final class UuidUtils {

    private UuidUtils() {
    }

    /** 32 位无横线 UUID（v4）。 */
    public static String random() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 标准 36 位带横线 UUID（v4），与文档示例格式一致。 */
    public static String randomWithDash() {
        return UUID.randomUUID().toString();
    }
}
