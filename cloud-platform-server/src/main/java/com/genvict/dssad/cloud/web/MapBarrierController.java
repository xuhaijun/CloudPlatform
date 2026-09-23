package com.genvict.dssad.cloud.web;

import com.genvict.dssad.cloud.common.api.ApiResponse;
import com.genvict.dssad.cloud.common.model.PageResult;
import com.genvict.dssad.cloud.config.AppProperties;
import com.genvict.dssad.cloud.service.MapBarrierService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 地图增强信息（交通阻断设施）接口。
 *
 * <p>数据来源有两条路，互为补充：
 * <ol>
 *   <li><b>推送</b>：监管平台通过 {@code mapehn/down} 主动下发（实时性好，但依赖对端）；</li>
 *   <li><b>拉取</b>：平台定时调用监管平台 {@code /manager/api/v1/event/mapehn} 兜底
 *       （推送丢失时仍能保证当天数据完整，见 {@link MapBarrierService#scheduledPull()}）。</li>
 * </ol>
 * 幂等键为 {@code barrierId + effectiveDate}，两条路的数据不会重复入库。
 */
@RestController
@RequestMapping("/api/v1/map-barriers")
@RequiredArgsConstructor
public class MapBarrierController {

    private final MapBarrierService mapBarrierService;
    private final AppProperties properties;

    /** 今日阻断设施列表（地图图层使用，一次取完当日全量，通常只有几十条）。 */
    @GetMapping
    public ApiResponse<List<MapBarrierService.BarrierView>> today() {
        return ApiResponse.ok(mapBarrierService.listByDate(LocalDate.now()));
    }

    /** 今日阻断设施分页（列表视图使用）。 */
    @GetMapping("/page")
    public ApiResponse<PageResult<MapBarrierService.BarrierView>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(mapBarrierService.pageToday(page, size, properties.api().maxPageSize()));
    }

    /** 指定日期的阻断设施（按天回看，用于事故复盘时还原当时的路况）。 */
    @GetMapping("/by-date")
    public ApiResponse<List<MapBarrierService.BarrierView>> byDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(mapBarrierService.listByDate(date));
    }

    /** 统计信息：今日条数（前端地图右上角角标）。 */
    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("date", LocalDate.now().toString());
        data.put("count", mapBarrierService.todayCount());
        return ApiResponse.ok(data);
    }

    /**
     * 手动触发一次拉取（运维排障用）。
     *
     * <p>定时任务每天执行一次；当发现监管平台更新了阻断信息但推送丢失时，
     * 运维可在前端点「同步」按钮立即拉取，无需等到第二天。
     */
    @PostMapping("/pull")
    public ApiResponse<Map<String, Object>> pull(@RequestParam(required = false) String areaCode) {
        int count = mapBarrierService.pullToday(areaCode);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fetched", count);
        return ApiResponse.ok(data);
    }
}
