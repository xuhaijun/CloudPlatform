package com.genvict.dssad.cloud.common.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CsvBuilder} 单元测试：重点钉死 RFC 4180 转义与 BOM ——
 * 这两处出错的表现是「Excel 打开串列/乱码」，很难在联调时发现。
 */
class CsvBuilderTest {

    @Test
    void plainCellsNeedNoQuoting() {
        byte[] bytes = CsvBuilder.of("a", "b").row("1", "2").toCsvBytes();
        String csv = new String(bytes, StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\ufeff").contains("a,b\r\n").contains("1,2\r\n");
    }

    @Test
    void commaQuoteAndNewlineAreEscaped() {
        byte[] bytes = CsvBuilder.of("desc")
                .row("含逗号,与\"引号\"和\n换行")
                .toCsvBytes();
        String csv = new String(bytes, StandardCharsets.UTF_8);
        assertThat(csv).contains("\"含逗号,与\"\"引号\"\"和\n换行\"");
    }

    @Test
    void nullCellBecomesEmpty() {
        String csv = new String(CsvBuilder.of("x").row(null, "v").toCsvBytes(), StandardCharsets.UTF_8);
        assertThat(csv).contains(",v\r\n");
    }
}
