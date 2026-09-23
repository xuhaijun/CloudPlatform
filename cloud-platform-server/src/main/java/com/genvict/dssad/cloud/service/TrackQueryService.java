package com.genvict.dssad.cloud.service;

import com.genvict.dssad.cloud.common.util.TrackSimplifier;
import com.genvict.dssad.cloud.config.AppProperties;
import com.genvict.dssad.cloud.domain.entity.VehicleTrackPoint;
import com.genvict.dssad.cloud.domain.repository.VehicleTrackPointRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 轨迹查询服务（{@code low-base} 数据的读取侧）。
 *
 * <p>把「原始 1Hz 数据」变成「前端可渲染的轨迹」需要三步，缺一不可：
 * <ol>
 *   <li><b>时间范围必填</b>：没有时间条件的查询在 1Hz 表上等于全表扫描；</li>
 *   <li><b>总量判定</b>：先 {@code count} 判断量级，量小直接全取（保真），
 *       量大才降级处理（保可用）。避免「为了可能不需要的精度」付出随机 IO 代价；</li>
 *   <li><b>两段式降采样</b>：
 *       <ul>
 *         <li>SQL 侧：按时间桶粗筛（{@code mod(ts, bucket) < 1000}），把行数压到万级；</li>
 *         <li>内存侧：Douglas-Peucker 抽稀，在保证形变在容差内的前提下继续压缩到千级。</li>
 *       </ul>
 *       只用 SQL 会保留大量「直线上冗余点」，只用抽稀则要先把几十万行读进内存。</li>
 * </ol>
 */
@Slf4j
@Service
public class TrackQueryService {

    /** 单次返回给前端的点数上限。 */
    private static final int DEFAULT_MAX_POINTS = 3_000;

    private final VehicleTrackPointRepository trackPointRepository;
    private final AppProperties.Api apiConfig;

    public TrackQueryService(VehicleTrackPointRepository trackPointRepository, AppProperties properties) {
        this.trackPointRepository = trackPointRepository;
        this.apiConfig = properties.api();
    }

    /**
     * 查询并抽稀轨迹。
     *
     * @param vin       车辆 VIN
     * @param fromMs    开始时间（毫秒，含）
     * @param toMs      结束时间（毫秒，含）
     * @param maxPoints 返回点数上限，{@code <=0} 时取默认值
     */
    @Transactional(readOnly = true)
    public TrackResult query(String vin, long fromMs, long toMs, int maxPoints) {
        int limit = maxPoints > 0 ? maxPoints : DEFAULT_MAX_POINTS;
        long rawCount = trackPointRepository.countByVinAndTsBetween(vin, fromMs, toMs);
        if (rawCount == 0) {
            return new TrackResult(List.of(), 0, 0, apiConfig.trackSimplifyTolerance(), vin, fromMs, toMs);
        }

        List<VehicleTrackPoint> raw;
        if (rawCount <= limit) {
            // 量小：全量取，不做任何抽稀，保证「小范围查询逐点还原」
            raw = trackPointRepository.findTrack(vin, fromMs, toMs, PageRequest.of(0, limit));
        } else {
            // 量大：先按时间桶粗筛，再抽稀
            long spanMillis = Math.max(1L, toMs - fromMs);
            long bucketMillis = alignBucket(spanMillis / limit);
            raw = trackPointRepository.findTrackBucketed(vin, fromMs, toMs, bucketMillis,
                    PageRequest.of(0, limit * 3));
            log.debug("[轨迹] 降采样 vin={} 原始点数={} 时间桶={}ms 粗筛后={}",
                    vin, rawCount, bucketMillis, raw.size());
        }

        List<VehicleTrackPoint> simplified = TrackSimplifier.simplify(
                raw, apiConfig.trackSimplifyTolerance(),
                new TrackSimplifier.PointAccessor<VehicleTrackPoint>() {
                    @Override
                    public double latitude(VehicleTrackPoint point) {
                        return point.getLatitude() == null ? 0.0 : point.getLatitude();
                    }

                    @Override
                    public double longitude(VehicleTrackPoint point) {
                        return point.getLongitude() == null ? 0.0 : point.getLongitude();
                    }
                });

        List<TrackPoint> points = new ArrayList<>(simplified.size());
        for (VehicleTrackPoint point : simplified) {
            points.add(new TrackPoint(point.getTs(), point.getLongitude(), point.getLatitude(),
                    point.getAltitude(), point.getSpeed(), point.getHeading(), point.getDrivingMode()));
        }
        log.info("[轨迹] 查询完成 vin={} [{} ~ {}] 原始={} 返回={} 抽稀容差={}m",
                vin, fromMs, toMs, rawCount, points.size(), apiConfig.trackSimplifyTolerance());
        return new TrackResult(points, (int) Math.min(rawCount, Integer.MAX_VALUE), points.size(),
                apiConfig.trackSimplifyTolerance(), vin, fromMs, toMs);
    }

    /** 当前位置（取时间范围内最后一条）。 */
    @Transactional(readOnly = true)
    public VehicleTrackPoint latest(String vin, long untilMs) {
        return trackPointRepository.findFirstByVinAndTsLessThanEqualOrderByTsDesc(vin, untilMs);
    }

    /**
     * 把时间桶对齐到 1 秒的整数倍。
     *
     * <p>数据是 1Hz 上报（ts 为整秒的毫秒值），策略 {@code mod(ts, bucket) < 1000}
     * 只有在 bucket 是 1000 的整数倍时才能保证每个桶恰好命中一次；
     * 否则会出现某些桶命中 0 次或 2 次，导致轨迹出现空洞或抖动。
     */
    private long alignBucket(long roughBucketMillis) {
        long seconds = Math.max(1L, roughBucketMillis / 1000L);
        return seconds * 1000L;
    }

    /**
     * 轨迹点（对外模型，字段名与协议保持一致，减少前后端认知成本）。
     *
     * @param ts          采样时间（毫秒）
     * @param longitude   经度
     * @param latitude    纬度
     * @param altitude    高程
     * @param speed       速度 km/h
     * @param heading     航向角
     * @param drivingMode 驾驶模式
     */
    public record TrackPoint(long ts, Double longitude, Double latitude, Double altitude,
                             Double speed, Double heading, Integer drivingMode) {
    }

    /**
     * 轨迹查询结果。
     *
     * @param points        抽稀后的轨迹点
     * @param rawCount      时间范围内原始点数
     * @param returnedCount 实际返回点数
     * @param tolerance     抽稀容差（米）
     * @param vin           车辆 VIN
     * @param fromMs        查询开始时间
     * @param toMs          查询结束时间
     */
    public record TrackResult(List<TrackPoint> points, int rawCount, int returnedCount,
                              double tolerance, String vin, long fromMs, long toMs) {
    }
}
