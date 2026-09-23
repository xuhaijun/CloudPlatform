package com.genvict.dssad.cloud.web;

import com.genvict.dssad.cloud.common.api.ApiResponse;
import com.genvict.dssad.cloud.common.exception.BizException;
import com.genvict.dssad.cloud.common.model.PageResult;
import com.genvict.dssad.cloud.config.AppProperties;
import com.genvict.dssad.cloud.domain.entity.Vehicle;
import com.genvict.dssad.cloud.domain.entity.VehicleStaticParam;
import com.genvict.dssad.cloud.domain.repository.VehicleStaticParamRepository;
import com.genvict.dssad.cloud.mqtt.dto.CarUpMessages;
import com.genvict.dssad.cloud.service.TelemetryService;
import com.genvict.dssad.cloud.service.TrackQueryService;
import com.genvict.dssad.cloud.service.VehicleService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 车辆接口：档案、实时状态、轨迹回放。
 *
 * <p>「车辆列表」是最频繁被打开的页面，因此每个接口都做了明确的性能取舍：
 * <ul>
 *   <li>列表只查 {@code t_vehicle} 单表（准静态参数已冗余到主档），无 JOIN；</li>
 *   <li>分页大小受 {@code dssad.api.max-page-size} 限制；</li>
 *   <li>轨迹接口强制要求时间范围，并自动降采样（见 {@link TrackQueryService}）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/vehicles")
@RequiredArgsConstructor
public class VehicleController {

    private final VehicleService vehicleService;
    private final TelemetryService telemetryService;
    private final TrackQueryService trackQueryService;
    private final VehicleStaticParamRepository staticParamRepository;
    private final AppProperties properties;

    /** 车辆列表（支持关键词、类型、在线状态过滤）。 */
    @GetMapping
    public ApiResponse<PageResult<Vehicle>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String vehicleType,
            @RequestParam(required = false) Boolean online,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(vehicleService.page(keyword, vehicleType, online, page, size));
    }

    /**
     * 车辆详情：档案 + 最新运行状态 + 当前位置。
     *
     * <p>一次请求返回三个视图所需数据，避免前端首屏发起 3 个请求（首屏请求数是移动端/弱网体验的关键）。
     */
    @GetMapping("/{vin}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String vin) {
        Vehicle vehicle = vehicleService.find(vin)
                .orElseThrow(() -> BizException.notFound("车辆不存在：" + vin));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("vehicle", vehicle);
        result.put("latestState", telemetryService.latestState(vin).orElse(null));
        result.put("position", telemetryService.currentPosition(vin).orElse(null));
        return ApiResponse.ok(result);
    }

    /** 最新运行状态（低频运行状态报文）。 */
    @GetMapping("/{vin}/state")
    public ApiResponse<CarUpMessages.LowState> latestState(@PathVariable String vin) {
        return ApiResponse.ok(telemetryService.latestState(vin).orElse(null));
    }

    /** 当前位置。 */
    @GetMapping("/{vin}/position")
    public ApiResponse<TelemetryService.Position> position(@PathVariable String vin) {
        return ApiResponse.ok(telemetryService.currentPosition(vin).orElse(null));
    }

    /** 运行状态历史分页。 */
    @GetMapping("/{vin}/state-history")
    public ApiResponse<PageResult<com.genvict.dssad.cloud.domain.entity.VehicleStateSnapshot>> stateHistory(
            @PathVariable String vin,
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        long now = System.currentTimeMillis();
        long from = now - Duration.ofHours(Math.min(Math.max(hours, 1), 720)).toMillis();
        return ApiResponse.ok(telemetryService.stateHistory(vin,
                com.genvict.dssad.cloud.common.util.TimeUtils.toInstant(from),
                com.genvict.dssad.cloud.common.util.TimeUtils.toInstant(now),
                page, size, properties.api().maxPageSize()));
    }

    /**
     * 轨迹回放。
     *
     * @param vin       车辆 VIN
     * @param startTime 开始时间（毫秒时间戳）
     * @param endTime   结束时间（毫秒时间戳）
     * @param maxPoints 最大返回点数（默认 3000）
     */
    @GetMapping("/{vin}/track")
    public ApiResponse<TrackQueryService.TrackResult> track(
            @PathVariable String vin,
            @RequestParam long startTime,
            @RequestParam long endTime,
            @RequestParam(defaultValue = "3000") int maxPoints) {
        if (endTime <= startTime) {
            throw BizException.paramInvalid("endTime 必须大于 startTime");
        }
        return ApiResponse.ok(trackQueryService.query(vin, startTime, endTime, maxPoints));
    }

    /** 准静态参数变更历史。 */
    @GetMapping("/{vin}/static-params")
    public ApiResponse<PageResult<VehicleStaticParam>> staticParams(
            @PathVariable String vin,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.min(Math.max(size, 1), properties.api().maxPageSize());
        int safePage = Math.max(page, 1);
        var result = staticParamRepository.findByVinOrderByReportedAtDesc(vin,
                PageRequest.of(safePage - 1, safeSize));
        return ApiResponse.ok(PageResult.of(result.getContent(), result.getTotalElements(), safePage, safeSize));
    }
}
