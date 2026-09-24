package com.genvict.dssad.cloud.common.util;

import java.nio.charset.StandardCharsets;

/**
 * 极简 CSV 构造器（仅用于服务端导出，不引入 OpenCSV 等三方依赖）。
 *
 * <p>遵循 RFC 4180：字段含逗号 / 双引号 / 换行时整体加引号并转义内部引号。
 * 输出统一带 UTF-8 BOM —— 没有它 Excel（Windows）按本地编码解析，中文必乱码，
 * 这是 CSV 导出最高频的线上反馈。
 */
public final class CsvBuilder {

    private final StringBuilder sb = new StringBuilder();

    private CsvBuilder(String... headers) {
        row((Object[]) headers);
    }

    public static CsvBuilder of(String... headers) {
        return new CsvBuilder(headers);
    }

    /** 追加一行；null 输出为空串，避免出现字面量 "null"。 */
    public CsvBuilder row(Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(cells[i] == null ? "" : String.valueOf(cells[i])));
        }
        sb.append("\r\n");
        return this;
    }

    /** RFC 4180 转义：特殊字符时整体包引号，内部双引号翻倍。 */
    private static String escape(String value) {
        boolean needsQuote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needsQuote) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    /** 带 BOM 的 UTF-8 字节流（Excel 兼容）。 */
    public byte[] toCsvBytes() {
        sb.insert(0, '\ufeff');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
