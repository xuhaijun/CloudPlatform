package com.genvict.dssad.cloud.service;

import com.genvict.dssad.cloud.common.model.PageResult;
import com.genvict.dssad.cloud.common.store.StateStore;
import com.genvict.dssad.cloud.common.util.JsonUtils;
import com.genvict.dssad.cloud.common.util.SnowflakeIdGenerator;
import com.genvict.dssad.cloud.common.util.TimeUtils;
import com.genvict.dssad.cloud.domain.entity.VehicleStateSnapshot;
import com.genvict.dssad.cloud.domain.entity.VehicleTrackPoint;
import com.genvict.dssad.cloud.domain.repository.VehicleStateSnapshotRepository;
import com.genvict.dssad.cloud.domain.repository.VehicleTrackPointRepository;
import com.genvict.dssad.cloud.mqtt.dto.CarUpMessages;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 车辆遥测服务（{@code low-state} + {@code low-base}）。
 *
 * <p>这是平台<b>吞吐压力最大</b>的入口：低频基础信息 1Hz/车。如果每条报文都同步走一次
 * 「JPA 事务 + 单条 INSERT」，千车规模下每秒 1000 次事务提交，数据库会成为瓶颈。
 *
 * <p>采用「<b>内存缓冲 + 定时批量落库</b>」：
 * <ol>
 *   <li>报文线程只做「组装实体 + 入队」，耗时在微秒级，不会阻塞 MQTT 回调线程；</li>
 *   <li>每秒由调度线程一次性 {@code saveAll} 刷盘，配合
 *       {@code hibernate.jdbc.batch_size} 与 MySQL 的 {@code rewriteBatchedStatements=true}
 *       合并为多值 INSERT，吞吐可提升 5~10 倍；</li>
 *   <li>缓冲有容量上限，超限丢弃并计数（宁可丢采样点也不能 OOM）；</li>
 *   <li>{@link PreDestroy} 与 {@link #flush()} 保证优雅停机不丢最后一批。</li>
 * </ol>
 *
 * <p>「最新状态」另外写入 {@link StateStore} 供列表页/大屏实时读取，
 * 避免列表页为每台车都查询「最近一条记录」。
 */
@Slf4j
@Service
public class TelemetryService {

    /** 单缓冲容量上限，超过则丢弃并告警。 */
    private static final int MAX_BUFFER_SIZE = 100_000;

    /** 批量刷盘的单批上限，避免单次事务过大。 */
    private static final int FLUSH_BATCH_SIZE = 5_000;

    private final VehicleTrackPointRepository trackPointRepository;
    private final VehicleStateSnapshotRepository stateSnapshotRepository;
    private final VehicleService vehicleService;
    private final StateStore stateStore;
    private final SnowflakeIdGenerator idGenerator;

    /** 最新状态/位置在缓存中的有效期（略大于上报周期，避免状态刚写入即过期）。 */
    private final Duration latestStateTtl = Duration.ofMinutes(30);

    private final Queue<VehicleTrackPoint> trackBuffer = new ConcurrentLinkedQueue<>();
    private final Queue<VehicleStateSnapshot> stateBuffer = new ConcurrentLinkedQueue<>();

    private final AtomicLong droppedTrackPoints = new AtomicLong();
    private final AtomicLong persistedTrackPoints = new AtomicLong();
    private final AtomicLong persistedStateSnapshots = new AtomicLong();

    public TelemetryService(VehicleTrackPointRepository trackPointRepository,
                            VehicleStateSnapshotRepository stateSnapshotRepository,
                            VehicleService vehicleService,
                            StateStore stateStore,
                            SnowflakeIdGenerator idGenerator) {
        this.trackPointRepository = trackPointRepository;
        this.stateSnapshotRepository = stateSnapshotRepository;
        this.vehicleService = vehicleService;
        this.stateStore = stateStore;
        this.idGenerator = idGenerator;
    }

    // ==================== 报文入口 ====================

    /**
     * 处理低频基础信息（1Hz 位置报）。
     *
     * <p>注意：本方法刻意<b>不加 {@code @Transactional}</b> —— 它只入队，不碰数据库。
     */
    public void onLowBase(String vin, String vehicleType, CarUpMessages.LowBase payload) {
        long ts = payload.timestamp() == null ? TimeUtils.nowMillis() : payload.timestamp();

        if (trackBuffer.size() >= MAX_BUFFER_SIZE) {
            long dropped = droppedTrackPoints.incrementAndGet();
            if (dropped % 10_000 == 1) {
                log.error("[遥测] 轨迹缓冲已满，丢弃采样点累计 {} 个（请检查数据库写入能力）", dropped);
            }
        } else {
            VehicleTrackPoint point = new VehicleTrackPoint();
            point.setId(idGenerator.nextId());
            point.setVin(vin);
            point.setTs(ts);
            point.setLongitude(payload.longitude());
            point.setLatitude(payload.latitude());
            point.setAltitude(payload.altitude());
            point.setSpeed(payload.speed());
            point.setAccSpeed(payload.accSpeed());
            point.setDrivingMode(payload.drivingMode());
            point.setHeading(payload.heading());
            trackBuffer.offer(point);
        }

        vehicleService.touchHeartbeat(vin, vehicleType);

        // 实时位置供前端「当前位置」与地图刷新使用
        stateStore.put("vehicle:position:" + vin,
                JsonUtils.toJson(new Position(ts, payload.longitude(), payload.latitude(),
                        payload.speed(), payload.heading(), payload.drivingMode())),
                latestStateTtl);
    }

    /** 处理低频运行状态（60s/次）。 */
    public void onLowState(String vin, CarUpMessages.LowState payload) {
        long ts = payload.timestamp() == null ? TimeUtils.nowMillis() : payload.timestamp();
        if (stateBuffer.size() < MAX_BUFFER_SIZE) {
            VehicleStateSnapshot snapshot = new VehicleStateSnapshot();
            snapshot.setId(idGenerator.nextId());
            snapshot.setVin(vin);
            snapshot.setMsgId(payload.msgId());
            snapshot.setReportedAt(TimeUtils.toInstant(ts));
            snapshot.setGnssStatus(payload.gnssStatus());
            snapshot.setVehicleTotalWeight(payload.vehicleTotalWeight());
            snapshot.setTirePressure(payload.tirePressure() == null
                    ? null : JsonUtils.toJson(payload.tirePressure()));
            snapshot.setEndurance(payload.endurance());
            snapshot.setMileage(payload.mileage());
            snapshot.setSoc(payload.soc());
            snapshot.setBatteryCapacity(payload.batteryCapacity());
            snapshot.setTemperature(payload.temperature());
            snapshot.setChargeState(payload.chargeState());
            stateBuffer.offer(snapshot);
        } else {
            log.error("[遥测] 状态缓冲已满，丢弃一条 low-state 报文 vin={}", vin);
        }
        stateStore.put("vehicle:state:" + vin, JsonUtils.toJson(payload), latestStateTtl);
    }

    // ==================== 批量刷盘 ====================

    /** 每秒刷盘一次。 */
    @Scheduled(fixedDelay = 1_000L)
    public void scheduledFlush() {
        flush();
    }

    /**
     * 执行一次刷盘。
     *
     * <p>对每条记录都做了「非空 @Id 检查」的替代方案是 {@code merge}，
     * 但 merge 会多一次 SELECT；本实体的主键由应用侧分配，故统一走 {@code persist} 语义。
     */
    @Transactional
    public void flush() {
        List<VehicleTrackPoint> points = drain(trackBuffer);
        if (!points.isEmpty()) {
            for (int i = 0; i < points.size(); i += FLUSH_BATCH_SIZE) {
                List<VehicleTrackPoint> batch = points.subList(i, Math.min(i + FLUSH_BATCH_SIZE, points.size()));
                trackPointRepository.saveAll(batch);
            }
            persistedTrackPoints.addAndGet(points.size());
        }

        List<VehicleStateSnapshot> snapshots = drain(stateBuffer);
        if (!snapshots.isEmpty()) {
            stateSnapshotRepository.saveAll(snapshots);
            persistedStateSnapshots.addAndGet(snapshots.size());
        }
    }

    @PreDestroy
    void flushOnShutdown() {
        int pending = trackBuffer.size() + stateBuffer.size();
        if (pending > 0) {
            log.info("[遥测] 服务关闭，刷盘剩余 {} 条遥测数据", pending);
            try {
                flush();
            } catch (RuntimeException e) {
                log.error("[遥测] 关闭时刷盘失败，将丢失 {} 条数据", pending, e);
            }
        }
    }

    private <T> List<T> drain(Queue<T> queue) {
        List<T> list = new ArrayList<>();
        T item;
        while ((item = queue.poll()) != null) {
            list.add(item);
        }
        return list;
    }

    // ==================== 查询与指标 ====================

    /** 当前位置（读缓存）。 */
    public Optional<Position> currentPosition(String vin) {
        return stateStore.get("vehicle:position:" + vin)
                .map(json -> JsonUtils.parse(json, Position.class));
    }

    /** 最新运行状态（读缓存，未命中时回落到数据库）。 */
    public Optional<CarUpMessages.LowState> latestState(String vin) {
        Optional<String> cached = stateStore.get("vehicle:state:" + vin);
        if (cached.isPresent()) {
            return Optional.of(JsonUtils.parse(cached.get(), CarUpMessages.LowState.class));
        }
        return stateSnapshotRepository.findFirstByVinOrderByReportedAtDesc(vin)
                .map(this::toDto);
    }

    /** 状态历史分页。 */
    @Transactional(readOnly = true)
    public PageResult<VehicleStateSnapshot> stateHistory(String vin, Instant from, Instant to,
                                                         int page, int size, int maxPageSize) {
        int safeSize = Math.min(Math.max(size, 1), maxPageSize);
        int safePage = Math.max(page, 1);
        Page<VehicleStateSnapshot> result = stateSnapshotRepository
                .findByVinAndReportedAtBetweenOrderByReportedAtDesc(vin, from, to,
                        PageRequest.of(safePage - 1, safeSize));
        return PageResult.of(result.getContent(), result.getTotalElements(), safePage, safeSize);
    }

    /** 当前缓冲积压（监控指标）。 */
    public int bufferedCount() {
        return trackBuffer.size() + stateBuffer.size();
    }

    /** 已丢弃的轨迹点总数。 */
    public long droppedCount() {
        return droppedTrackPoints.get();
    }

    /** 已落库的轨迹点总数。 */
    public long persistedTrackPoints() {
        return persistedTrackPoints.get();
    }

    /** 已落库的状态快照总数。 */
    public long persistedStateSnapshots() {
        return persistedStateSnapshots.get();
    }

    private CarUpMessages.LowState toDto(VehicleStateSnapshot entity) {
        return new CarUpMessages.LowState(
                entity.getVin(), entity.getMsgId(), TimeUtils.toEpochMillis(entity.getReportedAt()),
                entity.getGnssStatus(), entity.getVehicleTotalWeight(),
                entity.getTirePressure() == null ? List.of()
                        : JsonUtils.parse(entity.getTirePressure(), JsonUtils.listOf(Double.class)),
                entity.getEndurance(), entity.getMileage(), entity.getSoc(),
                entity.getBatteryCapacity(), entity.getTemperature(), entity.getChargeState());
    }

    /**
     * 车辆实时位置快照。
     *
     * @param ts          采样时间（毫秒）
     * @param longitude   经度（GCJ-02）
     * @param latitude    纬度（GCJ-02）
     * @param speed       速度 km/h
     * @param heading     航向角（度）
     * @param drivingMode 驾驶模式：0 自动驾驶 / 1 人工 / 2 远程接管
     */
    public record Position(long ts, Double longitude, Double latitude,
                           Double speed, Double heading, Integer drivingMode) {
    }
}
