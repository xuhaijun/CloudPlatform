package com.genvict.dssad.cloud.web;

import com.genvict.dssad.cloud.common.api.ApiResponse;
import com.genvict.dssad.cloud.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 运营总览（大屏首页）。
 *
 * <p>性能设计：总览涉及 5 张表的独立 COUNT/聚合，全部用<b>无 JOIN 的独立查询</b>再在内存拼装 ——
 * 比一条多表关联 SQL 更可预测（各表数据量差异极大：轨迹 1Hz、车辆只有几千行，
 * 让优化器去选执行计划反而容易走偏）。每个查询都命中索引，单次耗时在毫秒级。
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /** 总览卡片：车辆总数/在线数/今日事故/今日故障/待取证/地图阻断设施等。 */
    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        return ApiResponse.ok(dashboardService.overview());
    }

    /** 近 N 天趋势（事故数、故障数、任务数、在线车辆数）。 */
    @GetMapping("/trend")
    public ApiResponse<List<Map<String, Object>>> trend(@RequestParam(defaultValue = "7") int days) {
        return ApiResponse.ok(dashboardService.trend(days));
    }

    /** 故障分类分布（按字典的 5 大类聚合）。 */
    @GetMapping("/fault-categories")
    public ApiResponse<List<Map<String, Object>>> faultCategories(@RequestParam(defaultValue = "7") int days) {
        return ApiResponse.ok(dashboardService.faultCategoryDistribution(days));
    }

    /** 故障最多的车辆 TOP N（用于运维主动介入）。 */
    @GetMapping("/fault-vehicles")
    public ApiResponse<List<Map<String, Object>>> faultVehicles(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(dashboardService.topFaultVehicles(days, limit));
    }
}
