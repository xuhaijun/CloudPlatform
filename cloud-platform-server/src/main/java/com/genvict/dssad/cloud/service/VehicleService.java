package com.genvict.dssad.cloud.service;

import com.genvict.dssad.cloud.common.model.PageResult;
import com.genvict.dssad.cloud.common.util.SnowflakeIdGenerator;
import com.genvict.dssad.cloud.common.util.TimeUtils;
import com.genvict.dssad.cloud.config.AppProperties;
import com.genvict.dssad.cloud.domain.entity.Vehicle;
import com.genvict.dssad.cloud.domain.entity.VehicleStaticParam;
import com.genvict.dssad.cloud.domain.repository.VehicleRepository;
import com.genvict.dssad.cloud.domain.repository.VehicleStaticParamRepository;
import com.genvict.dssad.cloud.mqtt.dto.CarUpMessages;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 车辆档案与在线状态服务。
 *
 * <p>「自动建档」策略：协议中并未定义车辆注册接口（车端连上 Broker 就直接上报），
 * 因此平台在<b>首次收到某 VIN 的报文时自动建档</b>（{@code ensureVehicle}），
 * 后续报文只做更新。这避免了「报文先到、档案后建」导致的入库失败，
 * 也符合监管场景「数据不能因缺档案而丢失」的要求。车牌、车型等企业自有信息由管理端补充。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VehicleService {

    /** 心跳超时阈值：超过该时长未收到 low-base（1Hz）即判定离线。 */
    private static final Duration OFFLINE_THRESHOLD = Duration.ofMinutes(2);

    private final VehicleRepository vehicleRepository;
    private final VehicleStaticParamRepository staticParamRepository;
    private final SnowflakeIdGenerator idGenerator;
    private final AppProperties properties;
    private final com.genvict.dssad.cloud.common.store.StateStore stateStore;

    /**
     * 确保车辆档案存在（不存在则自动建档）。
     *
     * @param vin         车辆 VIN
     * @param vehicleType 车端类型（low_speed / high_speed），来自 Topic
     */
    @Transactional
    public Vehicle ensureVehicle(String vin, String vehicleType) {
        Optional<Vehicle> existing = vehicleRepository.findById(vin);
        if (existing.isPresent()) {
            return existing.get();
        }
        Vehicle vehicle = new Vehicle();
        vehicle.setVin(vin);
        vehicle.setVehicleType(vehicleType);
        vehicle.setEnterpriseId(properties.enterpriseId());
        vehicle.setOnline(Boolean.FALSE);
        log.info("[车辆] 首次收到报文，自动建档 vin={} vehicleType={}", vin, vehicleType);
        return vehicleRepository.save(vehicle);
    }

    /**
     * 应用准静态参数上报（{@code inh}）。
     *
     * <p>同时做两件事：更新车辆档案上的「最新值」+ 追加一条变更流水。
     * 用一条 UPDATE 语句 + 一条 INSERT 完成，避免加载整行再脏检查写回。
     */
    @Transactional
    public void applyStaticParam(String vin, String vehicleType, CarUpMessages.Inh payload) {
        Vehicle vehicle = ensureVehicle(vin, vehicleType);
        Instant reportedAt = TimeUtils.toInstant(
                payload.timestamp() == null ? TimeUtils.nowMillis() : payload.timestamp());

        vehicle.setHwVersion(payload.hwVersion());
        vehicle.setAdVersion(payload.adVersion());
        vehicle.setComType(payload.comType());
        vehicle.setTimeSync(payload.timeSync());
        vehicle.setLastInhAt(reportedAt);
        vehicleRepository.save(vehicle);

        VehicleStaticParam record = new VehicleStaticParam();
        record.setId(idGenerator.nextId());
        record.setVin(vin);
        record.setMsgId(payload.msgId());
        record.setReportedAt(reportedAt);
        record.setHwVersion(payload.hwVersion());
        record.setAdVersion(payload.adVersion());
        record.setComType(payload.comType());
        record.setTimeSync(payload.timeSync());
        staticParamRepository.save(record);
    }

    /**
     * 记录心跳（收到 {@code low-base} 时调用）。
     *
     * <p><b>性能关键</b>：这是平台上最高频的写操作（1Hz × 千车）。朴素实现
     * （{@code findById} 判断是否存在 → 加载实体 → 改字段 → 保存）
     * 每辆车每秒要产生 2 条 SQL 且更新全列。这里做了三处优化：
     * <ol>
     *   <li><b>存在性用状态存储短路</b>：车辆建档后 10 分钟内不再查库；</li>
     *   <li><b>心跳用单条 UPDATE</b>：只写 {@code online}/{@code last_base_at}/{@code updated_at} 三列；</li>
     *   <li><b>上下线判定读缓存</b>：不为了比较时间而回表查询。</li>
     * </ol>
     *
     * @return 本次是否发生了「离线 → 在线」的状态跃迁
     */
    @Transactional
    public boolean touchHeartbeat(String vin, String vehicleType) {
        if (stateStore.setIfAbsent(existKey(vin), "1", Duration.ofMinutes(10))) {
            ensureVehicle(vin, vehicleType);
        }
        Instant now = Instant.now();
        long previous = stateStore.get(lastBaseKey(vin)).map(Long::parseLong).orElse(0L);
        stateStore.put(lastBaseKey(vin), String.valueOf(now.toEpochMilli()), Duration.ofMinutes(10));
        vehicleRepository.touchOnline(vin, now);
        return previous == 0L || now.toEpochMilli() - previous > OFFLINE_THRESHOLD.toMillis();
    }

    private String existKey(String vin) {
        return "vehicle:exists:" + vin;
    }

    private String lastBaseKey(String vin) {
        return "vehicle:lastbase:" + vin;
    }

    /** 把超时未上报的车辆批量置为离线。 */
    @Transactional
    public int markOfflineVehicles() {
        Instant deadline = Instant.now().minus(OFFLINE_THRESHOLD);
        int affected = vehicleRepository.markOfflineBefore(deadline);
        if (affected > 0) {
            log.info("[车辆] {} 台车辆心跳超时，已置为离线", affected);
        }
        return affected;
    }

    /** 查询车辆（不存在返回 {@link Optional#empty()}）。 */
    @Transactional(readOnly = true)
    public Optional<Vehicle> find(String vin) {
        return vehicleRepository.findById(vin);
    }

    /**
     * 分页查询车辆（动态条件）。
     *
     * <p>用 {@link Specification} 组装条件而不是写 8 个 finder 方法：
     * 前端是「关键词 + 类型 + 在线状态 + 企业」的自由组合，组合数会爆炸。
     */
    @Transactional(readOnly = true)
    public PageResult<Vehicle> page(String keyword, String vehicleType, Boolean online, int page, int size) {
        Specification<Vehicle> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (StringUtils.hasText(keyword)) {
                String like = "%" + keyword.trim() + "%";
                predicates.add(cb.or(
                        cb.like(root.get("vin"), like),
                        cb.like(root.get("plateNo"), like),
                        cb.like(root.get("deviceSn"), like)));
            }
            if (StringUtils.hasText(vehicleType)) {
                predicates.add(cb.equal(root.get("vehicleType"), vehicleType));
            }
            if (online != null) {
                predicates.add(cb.equal(root.get("online"), online));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
        int safeSize = Math.min(Math.max(size, 1), properties.api().maxPageSize());
        int safePage = Math.max(page, 1);
        Page<Vehicle> result = vehicleRepository.findAll(spec,
                PageRequest.of(safePage - 1, safeSize, Sort.by(Sort.Direction.DESC, "lastBaseAt")));
        return PageResult.of(result.getContent(), result.getTotalElements(), safePage, safeSize);
    }

    /** 车辆总数。 */
    @Transactional(readOnly = true)
    public long total() {
        return vehicleRepository.count();
    }

    /** 在线车辆数。 */
    @Transactional(readOnly = true)
    public long onlineCount() {
        return vehicleRepository.countByOnlineTrue();
    }

    /** 事故计数自增。 */
    @Transactional
    public void increaseAccident(String vin) {
        vehicleRepository.increaseAccidentCount(vin);
    }

    /** 故障计数自增。 */
    @Transactional
    public void increaseFault(String vin, long delta) {
        vehicleRepository.increaseFaultCount(vin, delta);
    }
}
