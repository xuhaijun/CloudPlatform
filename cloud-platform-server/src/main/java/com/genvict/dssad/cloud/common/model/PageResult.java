package com.genvict.dssad.cloud.common.model;

import java.util.List;

/**
 * 统一分页结果。
 *
 * <p>约定：{@code page} 从 1 开始，{@code size} 上限由
 * {@code dssad.api.max-page-size} 控制（默认 200），防止前端一次拉全表拖垮数据库。
 *
 * @param list  当前页数据
 * @param total 满足条件的总记录数
 * @param page  当前页码（从 1 开始）
 * @param size  每页条数
 * @param <T>   元素类型
 */
public record PageResult<T>(List<T> list, long total, int page, int size) {

    /** 构造分页结果。 */
    public static <T> PageResult<T> of(List<T> list, long total, int page, int size) {
        return new PageResult<>(list, total, page, size);
    }

    /** 空页。 */
    public static <T> PageResult<T> empty(int page, int size) {
        return new PageResult<>(List.of(), 0L, page, size);
    }

    /** 总页数。 */
    public int totalPages() {
        return size <= 0 ? 0 : (int) ((total + size - 1) / size);
    }
}
