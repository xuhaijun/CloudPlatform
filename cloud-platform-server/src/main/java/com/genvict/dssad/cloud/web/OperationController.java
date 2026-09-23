package com.genvict.dssad.cloud.web;

import com.genvict.dssad.cloud.common.api.ApiResponse;
import com.genvict.dssad.cloud.common.exception.BizException;
import com.genvict.dssad.cloud.config.AppProperties;
import com.genvict.dssad.cloud.domain.entity.NavigationRoute;
import com.genvict.dssad.cloud.domain.entity.RemoteDrivingRecord;
import com.genvict.dssad.cloud.domain.entity.TrackTask;
import com.genvict.dssad.cloud.service.OperationService;
import com.genvict.dssad.cloud.service.RemoteCockpitService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.genvict.dssad.cloud.common.model.PageResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运营作业接口：轨迹跟踪任务、远程驾驶接管记录、导航路径。
 *
 * <p>三者都来自云云报文（{@code task/up}、{@code remote-driving/up}、{@code groute/up}），
 * 因此统一放在一个控制器里，便于前端按「运营作业」一个页面消费。
 */
@RestController
@RequestMapping("/api/v1/operations")
@RequiredArgsConstructor
public class OperationController {

    private final OperationService operationService;
    private final RemoteCockpitService remoteCockpitService;
    private final AppProperties properties;

    // ==================== 轨迹跟踪任务 ====================

    /** 任务列表（可按 VIN / 状态过滤）。 */
    @GetMapping("/tasks")
    public ApiResponse<PageResult<TrackTask>> tasks(
            @RequestParam(required = false) String vin,
            @RequestParam(required = false) TrackTask.TaskStatus status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(operationService.pageTasks(vin, status, page, size,
                properties.api().maxPageSize()));
    }

    // ==================== 远程驾驶接管 ====================

    /**
     * 远驾接管记录列表。
     *
     * @param closed {@code true} 只看已结束；不传或 {@code false} 只看进行中（默认）
     */
    @GetMapping("/remote-driving")
    public ApiResponse<PageResult<RemoteDrivingRecord>> remoteDriving(
            @RequestParam(required = false) String vin,
            @RequestParam(required = false, defaultValue = "false") Boolean closed,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(operationService.pageRemoteDriving(vin, closed, page, size,
                properties.api().maxPageSize()));
    }

    /**
     * 发起或结束一次轨迹跟踪任务（企业侧入口，向监管平台上报 6.1.4.2.2）。
     *
     * <p>企业自身调度系统派单时调用本接口，平台会同步做两件事：
     * 上报监管平台 {@code task/up}，并在本地留下任务记录，使前端「任务列表」有数据可查。
     */
    @PostMapping("/tasks")
    public ApiResponse<TrackTask> dispatchTask(@Valid @RequestBody TaskCommandRequest request) {
        return ApiResponse.ok(operationService.startTask(
                request.vin(), request.action(), request.taskId(),
                request.startPosition(), request.startPoint(),
                request.endPosition(), request.endPoint()));
    }

    /** 发起/结束任务的请求体。 */
    public record TaskCommandRequest(
            @NotBlank(message = "vin 不能为空") String vin,
            @NotBlank(message = "action 不能为空") String action,
            String taskId,
            String startPosition,
            List<Double> startPoint,
            String endPosition,
            List<Double> endPoint) {
    }

    /** 查询某车当前占用的远驾舱（从状态存储读，非落库数据）。 */
    @GetMapping("/vehicles/{vin}/cockpit")
    public ApiResponse<Map<String, Object>> currentCockpit(@PathVariable String vin) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("vin", vin);
        data.put("cockpitId", remoteCockpitService.currentCockpit(vin).orElse(null));
        return ApiResponse.ok(data);
    }

    /**
     * 平台内部发起远驾接管（区别于监管平台调用的对外接口）。
     *
     * <p>企业侧运维在发现车辆异常时也可以主动接管，此时同样需要向监管平台
     * 上报 {@code remote-driving/up}（type=1），保持监管侧状态一致。
     */
    @PostMapping("/vehicles/{vin}/cockpit/start")
    public ApiResponse<AppProperties.RemoteDriving.Cockpit> startCockpit(
            @PathVariable String vin,
            @RequestParam(defaultValue = "2") String starter) {
        remoteCockpitService.validateStarter(starter);
        AppProperties.RemoteDriving.Cockpit cockpit = remoteCockpitService.startTakeover(vin, starter);
        remoteCockpitService.bindVehicle(vin, cockpit.cockpitId());
        return ApiResponse.ok(cockpit);
    }

    /** 结束远驾接管并上报 type=2。 */
    @PostMapping("/vehicles/{vin}/cockpit/end")
    public ApiResponse<Boolean> endCockpit(@PathVariable String vin) {
        return ApiResponse.ok(remoteCockpitService.endTakeover(vin));
    }

    // ==================== 导航路径 ====================

    /** 导航路径分页（需指定 VIN）。 */
    @GetMapping("/routes")
    public ApiResponse<PageResult<NavigationRoute>> routes(
            @RequestParam String vin,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(operationService.pageRoutes(vin, page, size,
                properties.api().maxPageSize()));
    }

    /** 某车最近一条导航路径（轨迹回放叠加显示用）。 */
    @GetMapping("/routes/latest")
    public ApiResponse<NavigationRoute> latestRoute(@RequestParam String vin) {
        return ApiResponse.ok(operationService.latestRoute(vin)
                .orElseThrow(() -> BizException.notFound("该车辆暂无导航路径：" + vin)));
    }
}
