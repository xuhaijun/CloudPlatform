package com.genvict.dssad.cloud.service;

import com.genvict.dssad.cloud.common.cache.CacheNames;
import com.genvict.dssad.cloud.common.util.TimeUtils;
import com.genvict.dssad.cloud.domain.entity.AccidentEvent;
import com.genvict.dssad.cloud.domain.entity.TrackTask;
import com.genvict.dssad.cloud.domain.repository.AccidentEventRepository;
import com.genvict.dssad.cloud.domain.repository.FaultItemRepository;
import com.genvict.dssad.cloud.domain.repository.FaultRecordRepository;
import com.genvict.dssad.cloud.domain.repository.RemoteDrivingRecordRepository;
import com.genvict.dssad.cloud.domain.repository.TrackTaskRepository;
import com.genvict.dssad.cloud.domain.repository.VehicleRepository;
import com.genvict.dssad.cloud.mqtt.core.InboundMessageQueue;
import com.genvict.dssad.cloud.mqtt.core.OfflineMessageQueue;
import com.genvict.dssad.cloud.mqtt.core.PendingAckRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运营总览服务（大屏与首页统计）。
 *
 * <p>统计口径统一以<b>东八区自然日</b>为「今天」，与业务人员的直觉一致
 * （若按 UTC 切分，凌晨 8 点前的数据会被算到前一天）。
 *
 * <p>性能取舍：所有统计都走<b>独立 COUNT 查询</b>而不是把数据捞到内存里算，
 * 并且都命中索引（各表的 {@code error_time}/{@code reported_at} 等都有索引）。
 * 大屏每 10 秒刷新一次，单次总耗时在毫秒级。
 *
 * <p>即便如此，本类仍是<b>全平台查询密度最高</b>的入口：N 个前端 × 每 10 秒 × 4 个统计接口。
 * 因此四个只读方法全部接了二级缓存（见 {@link CacheNames}）：
 * <ul>
 *   <li>{@code overview} TTL 5 秒 —— 含实时队列水位（{@code mqttInboundQueue} 等），
 *       必须近实时，5 秒是「大屏看不出延迟」与「吸收刷新风暴」的平衡点；</li>
 *   <li>趋势/分类分布/故障榜 TTL 60 秒 —— 按天聚合的统计，秒级延迟无业务意义，
 *       拉长 TTL 把命中率做到接近 100%。</li>
 * </ul>
 *
 * <p><b>为什么不做写路径 {@code @CacheEvict}</b>：本类的数据源横跨 12 个仓储与 3 个队列，
 * 任何一处写入都要挂 evict，遗漏一处就会出现「统计永远停在旧值」的静默故障。
 * 统计数据的用户期待本身就不是强一致（大屏 10 秒刷新），
 * 用<b>短 TTL 收敛</b>比用<b>散落的 evict 钩子</b>更可靠 —— 后者只会在半年后被人忘掉。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final VehicleRepository vehicleRepository;
    private final AccidentEventRepository accidentRepository;
    private final FaultRecordRepository faultRecordRepository;
    private final FaultItemRepository faultItemRepository;
    private final TrackTaskRepository taskRepository;
    private final RemoteDrivingRecordRepository remoteDrivingRepository;
    private final MapBarrierService mapBarrierService;
    private final MqttAuditService auditService;
    private final EventService eventService;
    private final InboundMessageQueue inboundQueue;
    private final OfflineMessageQueue offlineQueue;
    private final PendingAckRegistry pendingAckRegistry;

    /** 首页/大屏总览。TTL 5 秒（含实时队列水位，需近实时）。 */
    @Cacheable(cacheNames = CacheNames.DASHBOARD_OVERVIEW, key = "'all'")
    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        Instant todayStart = TimeUtils.toInstant(todayStartMillis());
        Instant yesterdayStart = TimeUtils.toInstant(todayStartMillis() - Duration.ofDays(1).toMillis());

        Map<String, Object> result = new LinkedHashMap<>();
        // ---- 车辆 ----
        long totalVehicles = vehicleRepository.count();
        long onlineVehicles = vehicleRepository.countByOnlineTrue();
        result.put("vehicleTotal", totalVehicles);
        result.put("vehicleOnline", onlineVehicles);
        result.put("vehicleOffline", Math.max(0, totalVehicles - onlineVehicles));
        result.put("vehicleOnlineRate", totalVehicles == 0 ? 0.0
                : Math.round(onlineVehicles * 10000.0 / totalVehicles) / 100.0);
        result.put("vehicleByType", toTypeCountMap());

        // ---- 事件 ----
        long accidentToday = accidentRepository.countByOccurredAtAfter(todayStart);
        long accidentYesterday = accidentRepository.countByOccurredAtAfter(yesterdayStart) - accidentToday;
        result.put("accidentToday", accidentToday);
        result.put("accidentYesterday", accidentYesterday);
        result.put("accidentPendingMedia", accidentRepository.countByMediaStatus(AccidentEvent.MediaStatus.REPORTED)
                + accidentRepository.countByMediaStatus(AccidentEvent.MediaStatus.MEDIA_REQUESTED)
                + accidentRepository.countByMediaStatus(AccidentEvent.MediaStatus.MEDIA_ACCEPTED));

        // ---- 故障 ----
        long faultToday = faultRecordRepository.countByReportedAtAfter(todayStart);
        result.put("faultToday", faultToday);
        result.put("faultSevereToday", faultRecordRepository
                .countByMaxSeverityGreaterThanEqualAndReportedAtAfter(6, todayStart));
        result.put("faultTopCodes", eventService.topFaultCodes(Instant.now().minus(Duration.ofDays(7)), 10));

        // ---- 运营 ----
        result.put("taskRunning", taskRepository.countByStatus(TrackTask.TaskStatus.RUNNING));
        result.put("taskToday", taskRepository.findByStartedAtBetweenOrderByStartedAtDesc(
                todayStart, Instant.now(), PageRequest.of(0, 1)).getTotalElements());
        result.put("remoteDrivingToday", remoteDrivingRepository.countByOccurredAtAfter(todayStart));
        result.put("remoteDrivingActive", remoteDrivingRepository.findByClosedFalseOrderByOccurredAtDesc(
                PageRequest.of(0, 1)).getTotalElements());
        result.put("mapBarrierToday", mapBarrierService.todayCount());

        // ---- 链路健康 ----
        result.put("mqttRecords24h", auditService.stats(24 * 60).total());
        result.put("mqttInboundQueue", inboundQueue.size());
        result.put("mqttInboundDropped", inboundQueue.droppedCount());
        result.put("mqttOfflineQueue", offlineQueue.size());
        result.put("mqttPendingAck", pendingAckRegistry.size());
        result.put("generatedAt", TimeUtils.nowMillis());
        return result;
    }

    /**
     * 近 N 天事故/故障趋势。
     *
     * <p>实现方式：逐日窗口计数（每天 2 次 COUNT），而不是按日期 {@code GROUP BY}。
     * 原因：{@code DATE()} 等函数在 H2/MySQL 上语法与索引利用行为不同，
     * 按窗口计数虽然多几次查询，但<b>完全可移植且必然命中索引</b>，且 N 通常 ≤ 30。
     *
     * <p>TTL 60 秒：按天聚合的统计，秒级延迟没有业务意义。
     */
    @Cacheable(cacheNames = CacheNames.DASHBOARD_TREND, key = "#days")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> trend(int days) {
        int safeDays = Math.min(Math.max(days, 1), 90);
        List<Map<String, Object>> series = new ArrayList<>(safeDays);
        long dayMillis = Duration.ofDays(1).toMillis();
        long todayStart = todayStartMillis();
        for (int offset = safeDays - 1; offset >= 0; offset--) {
            long start = todayStart - offset * dayMillis;
            long end = start + dayMillis;
            Instant from = TimeUtils.toInstant(start);
            Instant to = TimeUtils.toInstant(end);
            long accidents = accidentRepository.countByOccurredAtAfter(from)
                    - accidentRepository.countByOccurredAtAfter(to);
            long faults = faultRecordRepository.countByReportedAtAfter(from)
                    - faultRecordRepository.countByReportedAtAfter(to);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", TimeUtils.format(start).substring(0, 10));
            point.put("accident", accidents);
            point.put("fault", faults);
            series.add(point);
        }
        return series;
    }

    /** 故障码分类分布（按附录 A.1 的 5 大类归集）。TTL 60 秒。 */
    @Cacheable(cacheNames = CacheNames.DASHBOARD_FAULT_CATEGORY, key = "#days")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> faultCategoryDistribution(int days) {
        Instant since = Instant.now().minus(Duration.ofDays(Math.min(Math.max(days, 1), 90)));
        List<Map<String, Object>> rows = eventService.topFaultCodes(since, 200);
        Map<String, Long> byCategory = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String label = String.valueOf(row.get("categoryLabel"));
            long count = ((Number) row.get("count")).longValue();
            byCategory.merge(label, count, Long::sum);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        byCategory.forEach((key, value) -> result.add(Map.of("category", key, "count", value)));
        return result;
    }

    /** 按车辆聚合的故障榜（重点运维名单）。TTL 60 秒。 */
    @Cacheable(cacheNames = CacheNames.DASHBOARD_FAULT_VEHICLES, key = "#days + ':' + #limit")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> topFaultVehicles(int days, int limit) {
        Instant since = Instant.now().minus(Duration.ofDays(Math.min(Math.max(days, 1), 90)));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : faultItemRepository.topFaultVehicles(since, PageRequest.of(0, limit))) {
            result.add(Map.of("vin", String.valueOf(row[0]), "count", ((Number) row[1]).longValue()));
        }
        return result;
    }

    private Map<String, Long> toTypeCountMap() {
        Map<String, Long> map = new LinkedHashMap<>();
        for (Object[] row : vehicleRepository.countOnlineGroupByType()) {
            map.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        return map;
    }

    /** 东八区「今天 00:00:00」的毫秒时间戳。 */
    private long todayStartMillis() {
        return TimeUtils.toLocalDateTime(TimeUtils.nowMillis())
                .toLocalDate().atStartOfDay(TimeUtils.ZONE_CN).toInstant().toEpochMilli();
    }
}
