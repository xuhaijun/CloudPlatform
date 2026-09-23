package com.genvict.dssad.cloud.service;

import com.genvict.dssad.cloud.common.model.PageResult;
import com.genvict.dssad.cloud.common.schedule.DistributedTaskLock;
import com.genvict.dssad.cloud.common.util.JsonUtils;
import com.genvict.dssad.cloud.common.util.TimeUtils;
import com.genvict.dssad.cloud.config.AppProperties;
import com.genvict.dssad.cloud.domain.entity.MapBarrier;
import com.genvict.dssad.cloud.domain.repository.MapBarrierRepository;
import com.genvict.dssad.cloud.integration.RegulatoryGateway;
import com.genvict.dssad.cloud.integration.dto.RegulatoryDtos;
import com.genvict.dssad.cloud.mqtt.dto.DownMessages;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 地图增强信息服务（交通阻断设施，文档 6.1.5.2.1 / 6.2.2.2.1）。
 *
 * <p>双通道接入：
 * <ul>
 *   <li><b>推送</b>：监管平台通过 MQTT {@code mapehn/down} 主动下发，
 *       平台落库后必须回复 {@code mapehn-resp/up}；</li>
 *   <li><b>拉取</b>：平台每天 00:05 通过 HTTP {@code /event/mapehn} 主动拉取当日全量，
 *       作为推送丢包时的兜底（MQTT 下行不保证送达，兜底拉取是必要的补偿机制）。</li>
 * </ul>
 *
 * <p>幂等键为 {@code (barrierId, effectiveDate)}：同一天同一阻断点被推送多次
 * （或推送与拉取都到达）时只更新内容，不产生重复记录，避免前端地图出现重叠图形。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MapBarrierService {

    private final MapBarrierRepository barrierRepository;
    private final RegulatoryGateway regulatoryGateway;
    private final AppProperties properties;
    private final DistributedTaskLock taskLock;

    /**
     * 处理 MQTT 推送的地图增强信息。
     *
     * @return 落库后的实体
     */
    @Transactional
    public MapBarrier onPush(DownMessages.Mapehn payload) {
        LocalDate today = LocalDate.now(TimeUtils.ZONE_CN);
        MapBarrier barrier = barrierRepository
                .findByBarrierIdAndEffectiveDate(payload.barrierId(), today)
                .orElseGet(MapBarrier::new);
        barrier.setEnterpriseId(payload.enterpriseId());
        barrier.setAreaCode(payload.areaCode());
        barrier.setBarrierId(payload.barrierId());
        barrier.setBarrierType(payload.barrierType());
        barrier.setBarrierName(payload.barrierName());
        barrier.setBarrierPolygon(JsonUtils.toJson(payload.barrierPolygon()));
        barrier.setDateType(payload.dateType());
        barrier.setTimeRange(JsonUtils.toJson(payload.timeRange()));
        barrier.setSpeedLimit(payload.speedLimit());
        barrier.setEffectiveDate(today);
        barrier.setSource(MapBarrier.Source.PUSH.name());
        barrier.setMsgId(payload.msgId());
        barrier.setReceivedAt(TimeUtils.toInstant(
                payload.timestamp() == null ? TimeUtils.nowMillis() : payload.timestamp()));
        barrier.setAcked(Boolean.FALSE);
        MapBarrier saved = barrierRepository.save(barrier);
        log.info("[地图增强] 收到推送 barrierId={} 类型={} 名称={} 限速={}",
                payload.barrierId(), payload.barrierType(), payload.barrierName(), payload.speedLimit());
        return saved;
    }

    /** 标记已回复 ACK。 */
    @Transactional
    public void markAcked(String barrierId) {
        LocalDate today = LocalDate.now(TimeUtils.ZONE_CN);
        barrierRepository.findByBarrierIdAndEffectiveDate(barrierId, today).ifPresent(barrier -> {
            barrier.setAcked(Boolean.TRUE);
            barrierRepository.save(barrier);
        });
    }

    /**
     * 拉取指定区域的今日地图增强信息（兜底机制）。
     *
     * @param areaCode 区域编码
     * @return 本次落库的条数
     */
    @Transactional
    public int pullToday(String areaCode) {
        RegulatoryDtos.MapehnResponse response = regulatoryGateway.fetchTodayMapehn(areaCode);
        if (response == null || response.barriers() == null) {
            log.info("[地图增强] 平台未返回今日阻断点 areaCode={}", areaCode);
            return 0;
        }
        LocalDate today = LocalDate.now(TimeUtils.ZONE_CN);
        int count = 0;
        for (RegulatoryDtos.Barrier item : response.barriers()) {
            MapBarrier barrier = barrierRepository
                    .findByBarrierIdAndEffectiveDate(item.barrierId(), today)
                    .orElseGet(MapBarrier::new);
            barrier.setEnterpriseId(properties.enterpriseId());
            barrier.setAreaCode(response.areaCode() == null ? areaCode : response.areaCode());
            barrier.setBarrierId(item.barrierId());
            barrier.setBarrierType(item.barrierType());
            barrier.setBarrierName(item.barrierName());
            barrier.setBarrierPolygon(JsonUtils.toJson(item.barrierPolygon()));
            barrier.setDateType(item.dateType());
            barrier.setTimeRange(JsonUtils.toJson(item.timeRange()));
            barrier.setSpeedLimit(item.speedLimit());
            barrier.setEffectiveDate(today);
            barrier.setSource(MapBarrier.Source.PULL.name());
            barrier.setReceivedAt(Instant.now());
            barrier.setAcked(Boolean.TRUE);
            barrierRepository.save(barrier);
            count++;
        }
        log.info("[地图增强] 拉取完成 areaCode={} 条数={}", areaCode, count);
        return count;
    }

    /**
     * 每日 00:05 拉取当日地图增强信息（推送通道的兜底）。
     *
     * <p>多实例部署时用分布式锁保证只有一个实例出网拉取（P-01）：
     * 重复拉取浪费监管平台配额，落库虽幂等但日志与 ACK 流量都会翻倍。
     */
    @Scheduled(cron = "0 5 0 * * ?")
    public void scheduledPull() {
        taskLock.runWithLock("map-barrier-pull", java.time.Duration.ofMinutes(10), () -> {
            try {
                // 区域编码为企业所在监管区域，生产环境应按车辆实际运营城市配置
                String areaCode = System.getProperty("dssad.area-code", "");
                pullToday(areaCode);
            } catch (RuntimeException e) {
                log.error("[地图增强] 定时拉取失败（将由下个周期重试）", e);
            }
        });
    }

    /** 查询指定日期的阻断点。 */
    @Transactional(readOnly = true)
    public List<BarrierView> listByDate(LocalDate date) {
        return barrierRepository.findByEffectiveDate(date).stream().map(this::toView).toList();
    }

    /** 今日阻断点分页。 */
    @Transactional(readOnly = true)
    public PageResult<BarrierView> pageToday(int page, int size, int maxPageSize) {
        int safeSize = Math.min(Math.max(size, 1), maxPageSize);
        int safePage = Math.max(page, 1);
        Page<MapBarrier> result = barrierRepository.findByEffectiveDateOrderByBarrierTypeAsc(
                LocalDate.now(ZoneId.of("Asia/Shanghai")), PageRequest.of(safePage - 1, safeSize));
        List<BarrierView> views = new ArrayList<>();
        result.getContent().forEach(item -> views.add(toView(item)));
        return PageResult.of(views, result.getTotalElements(), safePage, safeSize);
    }

    /** 今日阻断点数量（大屏用）。 */
    @Transactional(readOnly = true)
    public long todayCount() {
        return barrierRepository.countByEffectiveDate(LocalDate.now(TimeUtils.ZONE_CN));
    }

    private BarrierView toView(MapBarrier entity) {
        List<List<Double>> polygon = entity.getBarrierPolygon() == null ? List.of()
                : JsonUtils.parse(entity.getBarrierPolygon(), JsonUtils.listOfListOf(Double.class));
        List<List<String>> timeRange = entity.getTimeRange() == null ? List.of()
                : JsonUtils.parse(entity.getTimeRange(), JsonUtils.listOfListOf(String.class));
        return new BarrierView(
                entity.getBarrierId(),
                entity.getBarrierType(),
                "20".equals(entity.getBarrierType()) ? "禁行区域" : "限速区域",
                entity.getBarrierName(),
                entity.getAreaCode(),
                polygon,
                entity.getDateType(),
                timeRange,
                entity.getSpeedLimit(),
                entity.getSource(),
                entity.getAcked());
    }

    /**
     * 阻断点视图（把 JSON 还原为结构化数据返回给前端）。
     *
     * @param barrierId     编号
     * @param barrierType   类型：20 禁行 / 21 限速
     * @param barrierTypeLabel 类型中文
     * @param barrierName   名称
     * @param areaCode      区域编码
     * @param barrierPolygon 多边形顶点 {@code [[lat,lng],...]}
     * @param dateType      时间段类型
     * @param timeRange     生效时间段
     * @param speedLimit    限速值
     * @param source        来源：PUSH / PULL
     * @param acked         是否已回执
     */
    public record BarrierView(String barrierId, String barrierType, String barrierTypeLabel,
                              String barrierName, String areaCode, List<List<Double>> barrierPolygon,
                              String dateType, List<List<String>> timeRange,
                              Double speedLimit, String source, Boolean acked) {
    }
}
