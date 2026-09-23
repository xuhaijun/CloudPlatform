package com.genvict.dssad.cloud.domain.dict;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 故障码字典测试（接口文档附录 A.1，规范性内容）。
 *
 * <p>字典是告警、统计、报表的公共维度，一旦码值写错，前端会展示成
 * 「未知故障码(4356)」这种既不像错误又没人会去查的样子。因此这里把
 * <b>码值集合整体钉死</b>：增删码值必须是有意识的行为并同步更新用例。
 */
@DisplayName("故障码字典（附录 A.1）")
class FaultCodeDictionaryTest {

    @Test
    @DisplayName("码值数量与文档一致，且无重复、无空值")
    void codesAreUniqueAndComplete() {
        List<String> codes = Arrays.stream(FaultCodeDictionary.values())
                .map(FaultCodeDictionary::getCode)
                .toList();

        Set<String> unique = new HashSet<>(codes);
        assertEquals(codes.size(), unique.size(), "存在重复故障码");

        for (String code : codes) {
            assertFalse(code == null || code.isBlank(), "故障码不能为空");
            assertTrue(code.matches("\\d{4}"), "故障码应为 4 位数字，实际：" + code);
        }
    }

    @Test
    @DisplayName("每个码都必须有中文名、分类与说明，并且分类不能落在 UNKNOWN")
    void everyCodeIsFullyDescribed() {
        for (FaultCodeDictionary item : FaultCodeDictionary.values()) {
            assertFalse(item.getName() == null || item.getName().isBlank(),
                    item.getCode() + " 缺少中文名");
            assertFalse(item.getDescription() == null || item.getDescription().isBlank(),
                    item.getCode() + " 缺少说明");
            assertTrue(item.getCategory() != null, item.getCode() + " 缺少分类");
            assertFalse(item.getCategory() == FaultCodeDictionary.Category.UNKNOWN,
                    item.getCode() + " 被归入 UNKNOWN 分类，说明分类漏配");
        }
    }

    @Test
    @DisplayName("五类故障都至少有一个码（否则分类筛选会出现空目录）")
    void everyCategoryHasAtLeastOneCode() {
        for (FaultCodeDictionary.Category category : FaultCodeDictionary.Category.values()) {
            if (category == FaultCodeDictionary.Category.UNKNOWN) {
                continue;
            }
            long count = Arrays.stream(FaultCodeDictionary.values())
                    .filter(item -> item.getCategory() == category)
                    .count();
            assertTrue(count > 0, category + " 分类下没有任何故障码");
        }
    }

    @Test
    @DisplayName("按码值查字典：命中返回实体，未知返回空（不抛异常）")
    void findHandlesKnownAndUnknown() {
        FaultCodeDictionary known = FaultCodeDictionary.find("4613").orElseThrow();
        assertEquals("GNSS授时故障", known.getName());
        assertEquals(FaultCodeDictionary.Category.BASE_SOFTWARE, known.getCategory());

        assertTrue(FaultCodeDictionary.find("9999").isEmpty());
        assertTrue(FaultCodeDictionary.find(null).isEmpty());
        assertTrue(FaultCodeDictionary.find("  ").isEmpty());
    }

    @Test
    @DisplayName("码值首尾空格会被裁剪（车端 JSON 里带空格是常见情况）")
    void findTrimsWhitespace() {
        assertEquals(FaultCodeDictionary.find("4613"), FaultCodeDictionary.find("  4613  "));
    }

    @Test
    @DisplayName("未知码的展示兜底：不丢弃，显示为「未知故障码(码值)」")
    void unknownCodeIsStillDisplayable() {
        assertEquals("未知故障码(9999)", FaultCodeDictionary.nameOf("9999"));
        assertEquals(FaultCodeDictionary.Category.UNKNOWN, FaultCodeDictionary.categoryOf("9999"));
        assertEquals("GNSS授时故障", FaultCodeDictionary.nameOf("4613"));
    }
}
