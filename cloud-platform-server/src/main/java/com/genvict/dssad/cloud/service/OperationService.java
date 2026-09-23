package com.genvict.dssad.cloud.service;

import com.genvict.dssad.cloud.common.exception.BizException;
import com.genvict.dssad.cloud.common.model.PageResult;
import com.genvict.dssad.cloud.common.util.JsonUtils;
import com.genvict.dssad.cloud.common.util.TimeUtils;
import com.genvict.dssad.cloud.common.util.UuidUtils;
import com.genvict.dssad.cloud.config.AppProperties;
import com.genvict.dssad.cloud.domain.entity.NavigationRoute;
import com.genvict.dssad.cloud.domain.entity.RemoteDrivingRecord;
import com.genvict.dssad.cloud.domain.entity.TrackTask;
import com.genvict.dssad.cloud.domain.repository.NavigationRouteRepository;
import com.genvict.dssad.cloud.domain.repository.RemoteDrivingRecordRepository;
import com.genvict.dssad.cloud.domain.repository.TrackTaskRepository;
import com.genvict.dssad.cloud.mqtt.dto.EnterpriseUpMessages;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 运营协同服务：任务（{@code task}）、远程驾驶接管（{@code remote-driving}）、
 * 车端导航路径（{@code groute}）—— 即文档第 6.1.4.2 节的三个云云上行业务报文。
 *
 * <p>共性处理：
 * <ul>
 *   <li><b>配对语义</b>：任务与远驾都是「开始/结束」成对到达，平台需把它们归并为一条生命周期记录
 *       （见 {@link #onTask} / {@link #onRemoteDriving}）；</li>
 *   <li><b>坐标数组顺序</b>：协议规定 {@code [纬度, 经度]}，务必与 {@code currentPoint} 一致处理；</li>
 *   <li><b>时长冗余</b>：结束时计算并落库时长，避免列表页每次实时计算。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperationService {

    /** 任务动作：开始。 */
    public static final String TASK_ACTION_START = "1";
    /** 任务动作：结束。 */
    public static final String TASK_ACTION_END = "2";
    /** 远驾类型：发起接管。 */
    public static final String REMOTE_TYPE_START = "1";
    /** 远驾类型：结束接管。 */
    public static final String REMOTE_TYPE_END = "2";

    private final TrackTaskRepository taskRepository;
    private final RemoteDrivingRecordRepository remoteDrivingRepository;
    private final NavigationRouteRepository navigationRouteRepository;
    private final VehicleService vehicleService;
    private final MqttCommandService mqttCommandService;
    private final AppProperties properties;

    // ==================== 任务 ====================

    /**
     * 处理任务报文（开始/结束配对）。
     *
     * <p>「结束」报文可能先于「开始」到达（MQTT QoS 1 乱序、或平台刚重启）：
     * 此时不丢弃，而是创建一条 {@link TrackTask.TaskStatus#ORPHAN_FINISH} 记录并保留原始报文编号，
     * 便于运维核查数据缺失，而不是让这条监管数据「消失」。
     */
    @Transactional
    public TrackTask onTask(EnterpriseUpMessages.Task payload) {
        boolean isStart = TASK_ACTION_START.equals(payload.taskAction());
        Instant occurredAt = TimeUtils.toInstant(
                payload.timestamp() == null ? TimeUtils.nowMillis() : payload.timestamp());
        vehicleService.ensureVehicle(payload.vin(), null);

        Optional<TrackTask> existing = taskRepository.findByTaskId(payload.taskId());
        TrackTask task;
        if (isStart) {
            task = existing.orElseGet(TrackTask::new);
            task.setTaskId(payload.taskId());
            task.setEnterpriseId(payload.enterpriseId());
            task.setVin(payload.vin());
            task.setStartPosition(payload.taskStartPosition());
            applyPoint(payload.taskStartPoint(), task::setStartLatitude, task::setStartLongitude);
            task.setEndPosition(payload.taskEndPosition());
            applyPoint(payload.taskEndPoint(), task::setEndLatitude, task::setEndLongitude);
            task.setStartedAt(occurredAt);
            task.setStatus(TrackTask.TaskStatus.RUNNING);
            task.setLastMsgId(payload.msgId());
            log.info("[任务] 开始 taskId={} vin={} 起点={}", payload.taskId(), payload.vin(), payload.taskStartPosition());
        } else {
            task = existing.orElseGet(() -> {
                TrackTask orphan = new TrackTask();
                orphan.setTaskId(payload.taskId());
                orphan.setVin(payload.vin());
                orphan.setStatus(TrackTask.TaskStatus.ORPHAN_FINISH);
                log.warn("[任务] 收到结束报文但无开始记录，登记为异常任务 taskId={} vin={}",
                        payload.taskId(), payload.vin());
                return orphan;
            });
            task.setFinishedAt(occurredAt);
            if (task.getStatus() == TrackTask.TaskStatus.RUNNING) {
                task.setStatus(TrackTask.TaskStatus.FINISHED);
            }
            if (task.getStartedAt() != null) {
                task.setDurationSeconds(java.time.Duration.between(task.getStartedAt(), occurredAt).getSeconds());
            }
            task.setLastMsgId(payload.msgId());
            log.info("[任务] 结束 taskId={} 时长={}s 状态={}",
                    payload.taskId(), task.getDurationSeconds(), task.getStatus());
        }
        return taskRepository.save(task);
    }

    /**
     * 由企业侧发起或结束一次轨迹跟踪任务（文档 6.1.4.2.2）。
     *
     * <p>为什么必须同时「上报监管平台」和「本地落库」：
     * <ul>
     *   <li>上报是合规要求 —— 任务开始/结束必须让监管侧看到；</li>
     *   <li>落库是可用性要求 —— 只上报不落库时，平台自己的「任务列表」永远是空的，
     *       运维无法回答「这台车今天跑了哪几趟」，而监管侧一旦没收到报文，数据就彻底不存在。</li>
     * </ul>
     * 两条动作都在<b>同一个事务/同一个调用</b>内完成，避免出现「上报成功但本地无记录」的中间态。
     *
     * @param vin            车辆 VIN
     * @param action         1 开始 / 2 结束
     * @param taskId         任务号，为空时由平台生成（同一任务号多次「开始」按幂等处理）
     * @param startPosition  起点描述（开始任务时必传）
     * @param startPoint     起点坐标 [纬度, 经度]
     * @param endPosition    终点描述
     * @param endPoint       终点坐标 [纬度, 经度]
     */
    @Transactional
    public TrackTask startTask(String vin, String action, String taskId,
                              String startPosition, List<Double> startPoint,
                              String endPosition, List<Double> endPoint) {
        if (!TASK_ACTION_START.equals(action) && !TASK_ACTION_END.equals(action)) {
            throw BizException.paramInvalid("action 取值必须为 1（开始）或 2（结束）");
        }
        if (!StringUtils.hasText(vin)) {
            throw BizException.paramInvalid("vin 不能为空");
        }
        String resolvedTaskId = StringUtils.hasText(taskId) ? taskId : "TASK-" + UuidUtils.random();
        EnterpriseUpMessages.Task payload = new EnterpriseUpMessages.Task(
                properties.enterpriseId(), vin, UuidUtils.randomWithDash(), TimeUtils.nowMillis(),
                action, resolvedTaskId, startPosition, startPoint, endPosition, endPoint);
        String msgId = mqttCommandService.reportTask(payload);
        TrackTask task = onTask(payload);
        log.info("[任务] 企业侧下发 taskId={} vin={} action={} 上报msgId={}", resolvedTaskId, vin, action, msgId);
        return task;
    }

    // ==================== 远程驾驶接管 ====================

    /**
     * 处理远驾接管报文。
     *
     * <p>协议未提供接管单号，故「结束」报文通过「该车最近一条未结束的发起记录」配对。
     * 若找不到（如平台重启导致丢失），仍落一条结束记录并把数据保留下来。
     */
    @Transactional
    public RemoteDrivingRecord onRemoteDriving(EnterpriseUpMessages.RemoteDriving payload) {
        boolean isStart = REMOTE_TYPE_START.equals(payload.type());
        Instant occurredAt = TimeUtils.toInstant(
                payload.timestamp() == null ? TimeUtils.nowMillis() : payload.timestamp());
        vehicleService.ensureVehicle(payload.vin(), null);

        if (isStart) {
            RemoteDrivingRecord record = new RemoteDrivingRecord();
            record.setEnterpriseId(payload.enterpriseId());
            record.setVin(payload.vin());
            record.setDrivingType(payload.type());
            record.setDriver(payload.driver());
            record.setDriverCard(payload.card());
            record.setRemoteCockpitNo(payload.remoteCockpitNo());
            record.setOccurredAt(occurredAt);
            record.setMsgId(payload.msgId());
            record.setClosed(Boolean.FALSE);
            log.warn("[远驾] 发起接管 vin={} 驾驶员={} 台架号={} 时间={}",
                    payload.vin(), payload.driver(), payload.remoteCockpitNo(), TimeUtils.format(occurredAt));
            return remoteDrivingRepository.save(record);
        }

        RemoteDrivingRecord record = remoteDrivingRepository
                .findFirstByVinAndClosedFalseOrderByOccurredAtDesc(payload.vin())
                .orElseGet(() -> {
                    RemoteDrivingRecord orphan = new RemoteDrivingRecord();
                    orphan.setEnterpriseId(payload.enterpriseId());
                    orphan.setVin(payload.vin());
                    orphan.setOccurredAt(occurredAt);
                    log.warn("[远驾] 收到结束报文但无进行中的接管记录 vin={}", payload.vin());
                    return orphan;
                });
        record.setDrivingType(payload.type());
        record.setClosed(Boolean.TRUE);
        record.setDurationSeconds(java.time.Duration.between(record.getOccurredAt(), occurredAt).getSeconds());
        if (StringUtils.hasText(payload.driver())) {
            record.setDriver(payload.driver());
            record.setDriverCard(payload.card());
            record.setRemoteCockpitNo(payload.remoteCockpitNo());
        }
        record.setMsgId(payload.msgId());
        log.warn("[远驾] 结束接管 vin={} 时长={}s", payload.vin(), record.getDurationSeconds());
        return remoteDrivingRepository.save(record);
    }

    // ==================== 导航路径 ====================

    /** 保存车端导航路径（路径点整体存 JSON，冗余起终点与点数）。 */
    @Transactional
    public NavigationRoute onGroute(EnterpriseUpMessages.Groute payload) {
        List<List<Double>> points = payload.pathPoints() == null ? List.of() : payload.pathPoints();
        NavigationRoute route = new NavigationRoute();
        route.setEnterpriseId(payload.enterpriseId());
        route.setVin(payload.vin());
        route.setMsgId(payload.msgId());
        route.setReportedAt(TimeUtils.toInstant(
                payload.timestamp() == null ? TimeUtils.nowMillis() : payload.timestamp()));
        route.setPathPoints(JsonUtils.toJson(points));
        route.setPointCount(points.size());
        if (!points.isEmpty()) {
            List<Double> start = points.get(0);
            List<Double> end = points.get(points.size() - 1);
            if (start != null && start.size() >= 2) {
                route.setStartLatitude(start.get(0));
                route.setStartLongitude(start.get(1));
            }
            if (end != null && end.size() >= 2) {
                route.setEndLatitude(end.get(0));
                route.setEndLongitude(end.get(1));
            }
        }
        List<Double> current = payload.currentPoint();
        if (current != null && current.size() >= 2) {
            route.setCurrentLatitude(current.get(0));
            route.setCurrentLongitude(current.get(1));
        }
        vehicleService.ensureVehicle(payload.vin(), null);
        log.info("[导航] 收到路径 vin={} 点数={}", payload.vin(), points.size());
        return navigationRouteRepository.save(route);
    }

    // ==================== 查询 ====================

    /** 任务分页。 */
    @Transactional(readOnly = true)
    public PageResult<TrackTask> pageTasks(String vin, TrackTask.TaskStatus status,
                                          int page, int size, int maxPageSize) {
        Pageable pageable = buildPageable(page, size, maxPageSize);
        Page<TrackTask> result;
        if (StringUtils.hasText(vin)) {
            result = taskRepository.findByVinOrderByStartedAtDesc(vin, pageable);
        } else if (status != null) {
            result = taskRepository.findByStatusOrderByStartedAtDesc(status, pageable);
        } else {
            result = taskRepository.findAll(pageable);
        }
        return PageResult.of(result.getContent(), result.getTotalElements(),
                pageable.getPageNumber() + 1, pageable.getPageSize());
    }

    /**
     * 远驾接管记录分页。
     *
     * <p>两条筛选条件（车辆 / 是否已结束）<b>可任意组合</b>。
     * 早期实现存在两处缺陷，均已修正：
     * <ul>
     *   <li>「按车筛选」时 {@code closed} 被忽略 → 「进行中」列表里混进已结束记录；</li>
     *   <li>{@code closed=true} 时走 {@code findAll} → 返回的是全量记录，而非仅已结束。</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public PageResult<RemoteDrivingRecord> pageRemoteDriving(String vin, Boolean closed,
                                                            int page, int size, int maxPageSize) {
        Pageable pageable = buildPageable(page, size, maxPageSize);
        Page<RemoteDrivingRecord> result = remoteDrivingRepository.search(
                StringUtils.hasText(vin) ? vin : null,
                // 调用方只关心「进行中 / 已结束」二选一：未显式指定 closed 时默认只看进行中
                Boolean.TRUE.equals(closed) ? Boolean.TRUE : Boolean.FALSE,
                null, null, pageable);
        return PageResult.of(result.getContent(), result.getTotalElements(),
                pageable.getPageNumber() + 1, pageable.getPageSize());
    }

    /** 某车最近一条导航路径。 */
    @Transactional(readOnly = true)
    public Optional<NavigationRoute> latestRoute(String vin) {
        return navigationRouteRepository.findFirstByVinOrderByReportedAtDesc(vin);
    }

    /** 导航路径分页。 */
    @Transactional(readOnly = true)
    public PageResult<NavigationRoute> pageRoutes(String vin, int page, int size, int maxPageSize) {
        Pageable pageable = buildPageable(page, size, maxPageSize);
        Page<NavigationRoute> result = navigationRouteRepository.findByVinOrderByReportedAtDesc(vin, pageable);
        return PageResult.of(result.getContent(), result.getTotalElements(),
                pageable.getPageNumber() + 1, pageable.getPageSize());
    }

    private void applyPoint(List<Double> point,
                            java.util.function.Consumer<Double> latSetter,
                            java.util.function.Consumer<Double> lngSetter) {
        if (point == null || point.size() < 2) {
            return;
        }
        latSetter.accept(point.get(0));
        lngSetter.accept(point.get(1));
    }

    private Pageable buildPageable(int page, int size, int maxPageSize) {
        int safeSize = Math.min(Math.max(size, 1), maxPageSize);
        int safePage = Math.max(page, 1);
        return PageRequest.of(safePage - 1, safeSize);
    }
}
