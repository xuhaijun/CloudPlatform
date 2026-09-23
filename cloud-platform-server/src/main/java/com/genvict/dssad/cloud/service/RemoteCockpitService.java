package com.genvict.dssad.cloud.service;

import com.genvict.dssad.cloud.common.api.ErrorCode;
import com.genvict.dssad.cloud.common.exception.BizException;
import com.genvict.dssad.cloud.common.store.StateStore;
import com.genvict.dssad.cloud.common.util.TimeUtils;
import com.genvict.dssad.cloud.common.util.UuidUtils;
import com.genvict.dssad.cloud.config.AppProperties;
import com.genvict.dssad.cloud.mqtt.dto.EnterpriseUpMessages;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 远驾舱资源分配服务（支撑接口文档 6.3.2.2.1「远程驾驶接管发起」）。
 *
 * <p>业务背景：交巡警或监管平台在发现无人车异常时，可发起远程接管。
 * 企业云端收到请求后必须指派一个「远驾舱 + 驾驶员」，并把这次接管同步上报给监管平台
 * （文档 6.1.4.2.1 的 {@code remote-driving/up}，type=1）。
 *
 * <p>分配策略：<b>最久未使用（LRU）</b>。通过 {@link StateStore} 记录每个远驾舱的最近分配时间，
 * 每次取「最久没被用过」的那个 —— 既避免连续把任务压给同一个舱（疲劳风险），
 * 又不需要引入复杂的负载均衡器。分配记录同时写入状态存储用于并发去重。
 *
 * <p>兜底：资源池未配置时使用内置默认资源，保证接口在联调阶段可返回结构完整的响应；
 * 同时以 {@code FAILED} 级别日志提示运维补全配置（这是「不让配置缺失导致接口不可用」的工程取向）。
 */
@Slf4j
@Service
public class RemoteCockpitService {

    /** 远驾舱分配时间记录的 key 前缀。 */
    private static final String ALLOCATION_KEY_PREFIX = "cockpit:last-used:";

    /** 车辆当前接管舱的 key 前缀（用于结束接管时配对）。 */
    private static final String VEHICLE_COCKPIT_KEY_PREFIX = "cockpit:vin:";

    private static final Duration ALLOCATION_TTL = Duration.ofDays(7);

    /** 兜底远驾舱（未配置资源池时使用）。 */
    private static final AppProperties.RemoteDriving.Cockpit FALLBACK_COCKPIT =
            new AppProperties.RemoteDriving.Cockpit("YK-FALLBACK-01", "待分配", "", "", "");

    private final StateStore stateStore;
    private final MqttCommandService mqttCommandService;
    private final OperationService operationService;
    private final AppProperties.RemoteDriving config;
    private final String enterpriseId;

    public RemoteCockpitService(StateStore stateStore,
                                MqttCommandService mqttCommandService,
                                OperationService operationService,
                                AppProperties properties) {
        this.stateStore = stateStore;
        this.mqttCommandService = mqttCommandService;
        this.operationService = operationService;
        this.config = properties.remoteDriving();
        this.enterpriseId = properties.enterpriseId();
    }

    /**
     * 为车辆分配远驾舱并发起接管。
     *
     * @param vin     车辆 VIN
     * @param starter 发起人：1 交巡警 / 2 监管平台
     * @return 已分配的远驾舱资源
     */
    public AppProperties.RemoteDriving.Cockpit startTakeover(String vin, String starter) {
        AppProperties.RemoteDriving.Cockpit cockpit = allocate();
        // 同步上报监管平台（文档 6.1.4.2.1）
        EnterpriseUpMessages.RemoteDriving payload = new EnterpriseUpMessages.RemoteDriving(
                enterpriseId, vin, UuidUtils.randomWithDash(), TimeUtils.nowMillis(), "1",
                cockpit.driver(), cockpit.driverLicenseNumber(), cockpit.cockpitId());
        String msgId = mqttCommandService.reportRemoteDriving(payload);
        // 同时在本平台留下接管记录：远驾接管属于强监管行为，记录不能依赖对端 ACK 才产生
        // （监管平台抖动、resp 丢失时，企业侧仍需能自证「何时由谁接管了哪台车」）。
        operationService.onRemoteDriving(payload);
        log.warn("[远驾] 已受理接管请求 vin={} 发起人={} 分配舱={} 驾驶员={} 上报msgId={}",
                vin, starter, cockpit.cockpitId(), cockpit.driver(), msgId);
        return cockpit;
    }

    /**
     * 结束远驾接管。
     *
     * @param vin 车辆 VIN
     * @return 是否存在进行中的接管
     */
    public boolean endTakeover(String vin) {
        Optional<String> cockpitId = stateStore.get(VEHICLE_COCKPIT_KEY_PREFIX + vin);
        EnterpriseUpMessages.RemoteDriving payload = new EnterpriseUpMessages.RemoteDriving(
                enterpriseId, vin, UuidUtils.randomWithDash(), TimeUtils.nowMillis(), "2",
                null, null, cockpitId.orElse(null));
        mqttCommandService.reportRemoteDriving(payload);
        // 与发起侧对称：无论当前舱是否存在，都要把结束事件落一条记录，闭合这条监管数据
        operationService.onRemoteDriving(payload);
        stateStore.delete(VEHICLE_COCKPIT_KEY_PREFIX + vin);
        log.warn("[远驾] 已结束接管 vin={} 原舱={}", vin, cockpitId.orElse("未知"));
        return cockpitId.isPresent();
    }

    /** 查询车辆当前占用的远驾舱。 */
    public Optional<String> currentCockpit(String vin) {
        return stateStore.get(VEHICLE_COCKPIT_KEY_PREFIX + vin);
    }

    /**
     * 按 LRU 选择远驾舱。
     *
     * <p>实现：一次性读取所有候选舱的最近使用时间（{@link StateStore} 的多次 GET），
     * 取最小值对应的舱；候选数量通常为个位数，N 次 GET 的开销可忽略。
     */
    private AppProperties.RemoteDriving.Cockpit allocate() {
        List<AppProperties.RemoteDriving.Cockpit> pool = pool();
        AppProperties.RemoteDriving.Cockpit selected = pool.stream()
                .min(Comparator.comparingLong(this::lastUsedAt))
                .orElse(FALLBACK_COCKPIT);
        long now = TimeUtils.nowMillis();
        stateStore.put(ALLOCATION_KEY_PREFIX + selected.cockpitId(), String.valueOf(now), ALLOCATION_TTL);
        return selected;
    }

    /** 记录「某车辆被某舱接管」，供 {@link #endTakeover} 配对。 */
    public void bindVehicle(String vin, String cockpitId) {
        stateStore.put(VEHICLE_COCKPIT_KEY_PREFIX + vin, cockpitId, ALLOCATION_TTL);
    }

    private long lastUsedAt(AppProperties.RemoteDriving.Cockpit cockpit) {
        return stateStore.get(ALLOCATION_KEY_PREFIX + cockpit.cockpitId())
                .map(value -> {
                    try {
                        return Long.parseLong(value);
                    } catch (NumberFormatException e) {
                        return 0L;
                    }
                })
                .orElse(0L);
    }

    private List<AppProperties.RemoteDriving.Cockpit> pool() {
        AppProperties.RemoteDriving.Cockpit[] cockpits = config.cockpits();
        if (cockpits == null || cockpits.length == 0) {
            log.error("[远驾] 未配置远驾舱资源池（dssad.remote-driving.cockpits），已使用兜底资源返回，"
                    + "请在配置中补全以避免监管侧拿到占位数据");
            return List.of(FALLBACK_COCKPIT);
        }
        return Arrays.stream(cockpits)
                .filter(cockpit -> cockpit.cockpitId() != null && !cockpit.cockpitId().isBlank())
                .toList();
    }

    /** 校验发起人取值（文档：1 交巡警 / 2 监管平台）。 */
    public void validateStarter(String starter) {
        if (!"1".equals(starter) && !"2".equals(starter)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "starter 取值必须为 1（交巡警）或 2（监管平台）");
        }
    }
}
