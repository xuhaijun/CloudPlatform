package com.genvict.dssad.cloud.service;

import com.genvict.dssad.cloud.common.model.PageResult;
import com.genvict.dssad.cloud.common.util.TimeUtils;
import com.genvict.dssad.cloud.domain.dict.FaultCodeDictionary;
import com.genvict.dssad.cloud.domain.entity.AccidentEvent;
import com.genvict.dssad.cloud.domain.entity.FaultItem;
import com.genvict.dssad.cloud.domain.entity.FaultRecord;
import com.genvict.dssad.cloud.domain.entity.MediaAsset;
import com.genvict.dssad.cloud.domain.repository.AccidentEventRepository;
import com.genvict.dssad.cloud.domain.repository.FaultItemRepository;
import com.genvict.dssad.cloud.domain.repository.FaultRecordRepository;
import com.genvict.dssad.cloud.domain.repository.MediaAssetRepository;
import com.genvict.dssad.cloud.mqtt.dto.CarUpMessages;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 事件服务：事故事件（{@code accident}）与故障上报（{@code error-report}）。
 *
 * <p>三条业务规则贯穿本类：
 * <ol>
 *   <li><b>幂等</b>：车端对 QoS 1 报文会重发，故事故以车端 {@code eventId} 为幂等键，
 *       故障以 {@code (vin, msgId)} 语义做去重（去重键由 {@link MqttAuditService} 在入口处统一处理）；</li>
 *   <li><b>可追溯</b>：任何媒体归档动作都会回填到 {@link AccidentEvent} 的状态机上，
 *       保证「事故 → 请求媒体 → 车端受理 → 视频归档」四个环节的状态可查；</li>
 *   <li><b>字典化</b>：故障码统一经 {@link FaultCodeDictionary} 翻译，库内只存码值。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

    /** 事故事件状态机中「媒体尚未归档」的状态集合。 */
    private static final EnumSet<AccidentEvent.MediaStatus> PENDING_MEDIA_STATUSES = EnumSet.of(
            AccidentEvent.MediaStatus.REPORTED,
            AccidentEvent.MediaStatus.MEDIA_REQUESTED,
            AccidentEvent.MediaStatus.MEDIA_ACCEPTED);

    /** 导出单次最大行数：10 秒内可稳定序列化的规模上限，超出请缩小时间窗。 */
    public static final int EXPORT_MAX_ROWS = 10000;

    private final AccidentEventRepository accidentRepository;
    private final FaultRecordRepository faultRecordRepository;
    private final FaultItemRepository faultItemRepository;
    private final MediaAssetRepository mediaAssetRepository;
    private final VehicleService vehicleService;

    // ==================== 事故 ====================

    /**
     * 处理事故上报。
     *
     * <p>幂等实现：以车端 {@code eventId} 判定，已存在则<b>直接返回已有记录</b>
     * 并跳过重复入库与计数自增（车端重发场景下不能把计数加两遍）。
     *
     * @return 事故事件实体；调用方据此决定是否下发媒体请求
     */
    @Transactional
    public AccidentEvent onAccident(String vin, String vehicleType, CarUpMessages.Accident payload) {
        String eventId = StringUtils.hasText(payload.eventId())
                ? payload.eventId() : "AUTO-" + payload.msgId();
        Optional<AccidentEvent> existing = accidentRepository.findByEventId(eventId);
        if (existing.isPresent()) {
            log.info("[事故] 重复上报，忽略 eventId={} vin={}", eventId, vin);
            return existing.get();
        }

        AccidentEvent event = new AccidentEvent();
        event.setEventId(eventId);
        event.setVin(vin);
        event.setMsgId(payload.msgId());
        event.setOccurredAt(TimeUtils.toInstant(
                payload.timestamp() == null ? TimeUtils.nowMillis() : payload.timestamp()));
        event.setAccidentDesc(payload.accidentDesc());
        applyCurrentPoint(event, payload.currentPoint());
        event.setMediaStatus(AccidentEvent.MediaStatus.REPORTED);
        event.setMediaCount(0);
        AccidentEvent saved = accidentRepository.save(event);

        vehicleService.ensureVehicle(vin, vehicleType);
        vehicleService.increaseAccident(vin);
        log.warn("[事故] 收到事故上报 vin={} eventId={} 时间={} 位置=({},{}) 描述={}",
                vin, eventId, TimeUtils.format(saved.getOccurredAt()),
                saved.getLatitude(), saved.getLongitude(), payload.accidentDesc());
        return saved;
    }

    /**
     * 标记已下发媒体请求。
     *
     * @param eventId           事故 ID
     * @param requestMsgId      下发的 {@code accident-media/down} 消息编号
     * @param expectedDirectionCount 本次请求的方向数量，用于后续判断「是否收齐」
     */
    @Transactional
    public void markMediaRequested(String eventId, String requestMsgId, int expectedDirectionCount) {
        accidentRepository.findByEventId(eventId).ifPresent(event -> {
            event.setMediaStatus(AccidentEvent.MediaStatus.MEDIA_REQUESTED);
            event.setMediaRequestedAt(Instant.now());
            event.setMediaRequestMsgId(requestMsgId);
            event.setMediaExpectedCount(Math.max(expectedDirectionCount, 1));
            accidentRepository.save(event);
        });
    }

    /**
     * 处理车端对媒体请求的应答（{@code accident-media-resp}）。
     *
     * <p>用报文的 {@code ackMsgId} 反查事件：这正是「应用层 ACK」的价值 ——
     * 平台能精确知道哪一次媒体请求被受理，而不是靠时间窗口猜。
     */
    @Transactional
    public void onAccidentMediaResp(String vin, CarUpMessages.AccidentMediaResp payload) {
        Optional<AccidentEvent> found = accidentRepository.findFirstByMediaRequestMsgId(payload.ackMsgId());
        if (found.isEmpty()) {
            log.warn("[事故] 收到媒体应答但未匹配到事件 vin={} ackMsgId={}", vin, payload.ackMsgId());
            return;
        }
        AccidentEvent event = found.get();
        if (payload.accepted()) {
            event.setMediaStatus(AccidentEvent.MediaStatus.MEDIA_ACCEPTED);
            log.info("[事故] 车端已受理媒体请求 eventId={} vin={}，等待 HTTP 上传视频", event.getEventId(), vin);
        } else {
            event.setMediaStatus(AccidentEvent.MediaStatus.MEDIA_FAILED);
            log.error("[事故] 车端拒绝媒体请求 eventId={} vin={} res={}",
                    event.getEventId(), vin, payload.res());
        }
        accidentRepository.save(event);
    }

    /**
     * 归档一段事故视频并推进状态。
     *
     * <p>「是否收齐」以事件自身记录的 {@code mediaExpectedCount} 为准（由下发请求时写入），
     * 而不是由调用方传入 —— 避免不同调用点传入不一致的期望值导致状态机错乱。
     *
     * @param eventId         事故 ID
     * @param cameraDirection 方向：1 前 / 2 右 / 3 后 / 4 左
     */
    @Transactional
    public MediaAsset archiveAccidentMedia(String eventId, String cameraDirection,
                                          String storagePath, String url, Long fileSize,
                                          String mediaType) {
        AccidentEvent event = accidentRepository.findByEventId(eventId)
                .orElseThrow(() -> com.genvict.dssad.cloud.common.exception.BizException
                        .notFound("事故事件不存在：" + eventId));

        MediaAsset asset = new MediaAsset();
        asset.setVin(event.getVin());
        asset.setEventId(eventId);
        asset.setCameraDirection(cameraDirection);
        asset.setMediaType(mediaType);
        asset.setStoragePath(storagePath);
        asset.setUrl(url);
        asset.setFileSize(fileSize);
        asset.setKind(MediaAsset.Kind.ACCIDENT.name());
        asset.setStatus(MediaAsset.Status.AVAILABLE.name());
        asset.setUploadedBy(event.getVin());
        asset.setRequestMsgId(event.getMediaRequestMsgId());
        // 事故视频：事发前 15 秒 ~ 后 5 秒（文档 3.3）
        asset.setStartTime(event.getOccurredAt().minusSeconds(15));
        asset.setEndTime(event.getOccurredAt().plusSeconds(5));
        MediaAsset saved = mediaAssetRepository.save(asset);

        int archived = (int) mediaAssetRepository.countByEventId(eventId);
        event.setMediaCount(archived);
        // 方向数按「收到几个不同方向」统计更准确：同一方向重复上传不应算作收齐
        long distinctDirections = mediaAssetRepository.countDistinctDirectionByEventId(eventId);
        int expected = event.getMediaExpectedCount() == null ? 0 : event.getMediaExpectedCount();
        if (expected > 0 && distinctDirections >= expected) {
            event.setMediaStatus(AccidentEvent.MediaStatus.MEDIA_ARCHIVED);
            log.info("[事故] 视频归档完成 eventId={} 已收方向数={}/{} 文件数={}",
                    eventId, distinctDirections, expected, archived);
        } else {
            log.info("[事故] 视频已收到，等待其他方向 eventId={} 已收方向数={}/{}",
                    eventId, distinctDirections, expected);
        }
        accidentRepository.save(event);
        return saved;
    }

    // ==================== 故障 ====================

    /**
     * 处理故障上报，落库为「主记录 + N 条明细」。
     *
     * <p>同时计算本次上报的最严重等级冗余到主表：故障中心列表按严重程度排序是最高频查询，
     * 若放在明细表会导致每次列表查询都要 JOIN + 聚合。
     */
    @Transactional
    public FaultRecord onFaultReport(String vin, CarUpMessages.FaultReport payload) {
        Instant reportedAt = TimeUtils.toInstant(
                payload.timestamp() == null ? TimeUtils.nowMillis() : payload.timestamp());

        FaultRecord record = new FaultRecord();
        record.setVin(vin);
        record.setMsgId(payload.msgId());
        record.setReportedAt(reportedAt);
        List<CarUpMessages.FaultItem> items = payload.errorData() == null ? List.of() : payload.errorData();
        record.setErrorNum(payload.errorNum() == null ? items.size() : payload.errorNum());
        record.setMaxSeverity(items.stream()
                .mapToInt(item -> parseSeverity(item.errorSeverity()))
                .max().orElse(0));
        applyCurrentPoint(record, payload.currentPoint());

        for (CarUpMessages.FaultItem item : items) {
            FaultItem entity = new FaultItem();
            entity.setVin(vin);
            entity.setErrorCode(item.errorCode());
            entity.setErrorTimestamp(item.errorTimestamp());
            entity.setErrorSeverity(item.errorSeverity());
            entity.setMsgCount(item.msgcount());
            entity.setErrorReason(item.errorReason());
            record.addItem(entity);
        }
        FaultRecord saved = faultRecordRepository.save(record);

        vehicleService.increaseFault(vin, Math.max(1, saved.getErrorNum()));
        log.warn("[故障] 收到故障上报 vin={} 故障数={} 最严重等级={} 明细={}",
                vin, saved.getErrorNum(), saved.getMaxSeverity(),
                items.stream().map(i -> i.errorCode() + "(" + FaultCodeDictionary.nameOf(i.errorCode()) + ")")
                        .toList());
        return saved;
    }

    // ==================== 查询 ====================

    /**
     * 事故事件分页查询。
     *
     * <p>三个筛选条件（车辆 / 取证状态 / 时间窗）<b>可任意组合</b>。
     * 早期版本按「if / else if」二选一，导致同时传 vin 与 mediaStatus 时后者被忽略，
     * 前端看到的列表与筛选条件不符。现统一走一条条件全为可选的查询。
     */
    @Transactional(readOnly = true)
    public PageResult<AccidentEvent> pageAccidents(String vin, AccidentEvent.MediaStatus mediaStatus,
                                                  Instant from, Instant to, int page, int size, int maxPageSize) {
        Pageable pageable = buildPageable(page, size, maxPageSize);
        Page<AccidentEvent> result = accidentRepository.search(
                StringUtils.hasText(vin) ? vin : null, mediaStatus, from, to, pageable);
        return PageResult.of(result.getContent(), result.getTotalElements(),
                pageable.getPageNumber() + 1, pageable.getPageSize());
    }

    /** 事故详情（含媒体清单）。 */
    @Transactional(readOnly = true)
    public Optional<AccidentEvent> findAccident(String eventId) {
        return accidentRepository.findByEventId(eventId);
    }

    /** 某事故的视频清单。 */
    public List<MediaAsset> accidentMedia(String eventId) {
        return mediaAssetRepository.findByEventId(eventId);
    }

    /** 待归档事故（告警巡检用）。 */
    @Transactional(readOnly = true)
    public List<AccidentEvent> pendingAccidents(Instant before, int limit) {
        return accidentRepository.findUnarchivedBefore(PENDING_MEDIA_STATUSES, before, PageRequest.of(0, limit));
    }

    /** 故障记录分页（车辆 / 最低严重等级 / 时间窗可任意组合）。 */
    @Transactional(readOnly = true)
    public PageResult<FaultRecord> pageFaults(String vin, Integer minSeverity,
                                             Instant from, Instant to, int page, int size, int maxPageSize) {
        Pageable pageable = buildPageable(page, size, maxPageSize);
        Page<FaultRecord> result = faultRecordRepository.search(
                StringUtils.hasText(vin) ? vin : null, minSeverity, from, to, pageable);
        return PageResult.of(result.getContent(), result.getTotalElements(),
                pageable.getPageNumber() + 1, pageable.getPageSize());
    }

    /** 故障详情（含明细，一次性抓取避免 N+1）。 */
    @Transactional(readOnly = true)
    public Optional<FaultRecord> findFault(Long id) {
        return faultRecordRepository.findWithItems(id);
    }

    /**
     * 事故清单导出（CSV 用）：与 {@link #pageAccidents} 同一套筛选条件，
     * 但不分页 —— 以「单次最多 {@value #EXPORT_MAX_ROWS} 行」为硬上限。
     *
     * <p>上限是必须的：不设上限的导出既是慢查询放大器（全表扫描 +
     * 数万行序列化占满虚拟线程），也是内存放大器。
     */
    @Transactional(readOnly = true)
    public List<AccidentEvent> listAccidentsForExport(String vin, AccidentEvent.MediaStatus mediaStatus,
                                                      Instant from, Instant to) {
        Pageable limit = PageRequest.of(0, EXPORT_MAX_ROWS);
        return accidentRepository.search(
                StringUtils.hasText(vin) ? vin : null, mediaStatus, from, to, limit).getContent();
    }

    /** 故障清单导出（CSV 用），约束同 {@link #listAccidentsForExport}。 */
    @Transactional(readOnly = true)
    public List<FaultRecord> listFaultsForExport(String vin, Integer minSeverity,
                                                 Instant from, Instant to) {
        Pageable limit = PageRequest.of(0, EXPORT_MAX_ROWS);
        return faultRecordRepository.search(
                StringUtils.hasText(vin) ? vin : null, minSeverity, from, to, limit).getContent();
    }

    /** 近 N 天故障码 Top 榜。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> topFaultCodes(Instant since, int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : faultItemRepository.topErrorCodes(since, PageRequest.of(0, limit))) {
            String code = String.valueOf(row[0]);
            result.add(Map.of(
                    "errorCode", code,
                    "name", FaultCodeDictionary.nameOf(code),
                    "category", FaultCodeDictionary.categoryOf(code).name(),
                    "categoryLabel", FaultCodeDictionary.categoryOf(code).label(),
                    "count", ((Number) row[1]).longValue(),
                    "maxSeverity", row[2] == null ? 0 : Integer.parseInt(String.valueOf(row[2]))));
        }
        return result;
    }

    // ==================== 内部工具 ====================

    /**
     * 解析 {@code currentPoint}（协议规定：数组第一位是纬度，第二位是经度）。
     *
     * <p>这是最容易写反的地方，故抽成方法并写清注释；同时做长度与空值防护，
     * 避免脏数据导致「纬度 130、经度 22」这种看似正常实则错位的记录入库。
     */
    private void applyCurrentPoint(AccidentEvent event, List<Double> point) {
        if (point == null || point.size() < 2) {
            return;
        }
        event.setLatitude(point.get(0));
        event.setLongitude(point.get(1));
    }

    private void applyCurrentPoint(FaultRecord record, List<Double> point) {
        if (point == null || point.size() < 2) {
            return;
        }
        record.setLatitude(point.get(0));
        record.setLongitude(point.get(1));
    }

    private int parseSeverity(String severity) {
        if (severity == null) {
            return 0;
        }
        try {
            return Integer.parseInt(severity.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Pageable buildPageable(int page, int size, int maxPageSize) {
        int safeSize = Math.min(Math.max(size, 1), maxPageSize);
        int safePage = Math.max(page, 1);
        return PageRequest.of(safePage - 1, safeSize);
    }
}
