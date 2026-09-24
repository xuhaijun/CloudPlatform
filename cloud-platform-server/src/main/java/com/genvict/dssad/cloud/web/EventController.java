package com.genvict.dssad.cloud.web;

import com.genvict.dssad.cloud.common.api.ApiResponse;
import com.genvict.dssad.cloud.common.exception.BizException;
import com.genvict.dssad.cloud.common.model.PageResult;
import com.genvict.dssad.cloud.common.util.CsvBuilder;
import com.genvict.dssad.cloud.common.util.TimeUtils;
import com.genvict.dssad.cloud.config.AppProperties;
import com.genvict.dssad.cloud.domain.entity.AccidentEvent;
import com.genvict.dssad.cloud.domain.entity.FaultItem;
import com.genvict.dssad.cloud.domain.entity.FaultRecord;
import com.genvict.dssad.cloud.domain.entity.MediaAsset;
import com.genvict.dssad.cloud.service.EventService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事件接口：事故中心与故障中心。
 */
@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;
    private final AppProperties properties;

    /** 事故事件列表。 */
    @GetMapping("/accidents")
    public ApiResponse<PageResult<AccidentEvent>> accidents(
            @RequestParam(required = false) String vin,
            @RequestParam(required = false) AccidentEvent.MediaStatus mediaStatus,
            @RequestParam(defaultValue = "168") int hours,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        long now = System.currentTimeMillis();
        long from = now - Duration.ofHours(Math.min(Math.max(hours, 1), 2160)).toMillis();
        return ApiResponse.ok(eventService.pageAccidents(vin, mediaStatus,
                TimeUtils.toInstant(from), TimeUtils.toInstant(now),
                page, size, properties.api().maxPageSize()));
    }

    /** 事故详情（含媒体归档清单）。 */
    @GetMapping("/accidents/{eventId}")
    public ApiResponse<Map<String, Object>> accidentDetail(@PathVariable String eventId) {
        AccidentEvent event = eventService.findAccident(eventId)
                .orElseThrow(() -> BizException.notFound("事故事件不存在：" + eventId));
        List<MediaAsset> media = eventService.accidentMedia(eventId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("event", event);
        result.put("media", media);
        // 取证要素齐备性提示：文档 3.3 要求四方向视频，缺失时前端应显著提示
        result.put("mediaComplete", media.size() >= 4);
        return ApiResponse.ok(result);
    }

    /** 待归档事故（运维修正清单）。 */
    @GetMapping("/accidents/pending")
    public ApiResponse<List<AccidentEvent>> pendingAccidents(
            @RequestParam(defaultValue = "10") int timeoutMinutes,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(eventService.pendingAccidents(
                java.time.Instant.now().minus(Duration.ofMinutes(Math.max(timeoutMinutes, 1))),
                Math.min(Math.max(limit, 1), 200)));
    }

    /** 故障记录列表。 */
    @GetMapping("/faults")
    public ApiResponse<PageResult<FaultRecord>> faults(
            @RequestParam(required = false) String vin,
            @RequestParam(required = false) Integer minSeverity,
            @RequestParam(defaultValue = "168") int hours,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        long now = System.currentTimeMillis();
        long from = now - Duration.ofHours(Math.min(Math.max(hours, 1), 2160)).toMillis();
        return ApiResponse.ok(eventService.pageFaults(vin, minSeverity,
                TimeUtils.toInstant(from), TimeUtils.toInstant(now),
                page, size, properties.api().maxPageSize()));
    }

    /**
     * 故障详情（含明细，一次性抓取）。
     *
     * <p>结构与事故详情保持一致：{@code {record, items, itemCount}}。
     * 列表接口不返回明细（见 {@code FaultRecord#items} 的说明），
     * 因此需要看明细时必须走本接口，避免把懒加载集合带进列表序列化路径。
     */
    @GetMapping("/faults/{id}")
    public ApiResponse<Map<String, Object>> faultDetail(@PathVariable Long id) {
        FaultRecord record = eventService.findFault(id)
                .orElseThrow(() -> BizException.notFound("故障记录不存在：" + id));
        List<FaultItem> items = record.getItems();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("record", record);
        result.put("items", items);
        result.put("itemCount", items.size());
        return ApiResponse.ok(result);
    }

    /** 故障码 Top 榜（按附录 A.1 字典翻译）。 */
    @GetMapping("/faults/top-codes")
    public ApiResponse<List<Map<String, Object>>> topFaultCodes(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(eventService.topFaultCodes(
                java.time.Instant.now().minus(Duration.ofDays(Math.min(Math.max(days, 1), 90))),
                Math.min(Math.max(limit, 1), 100)));
    }

    // ==================== CSV 导出（监管上报 / 离线分析刚需） ====================

    /**
     * 事故清单导出（CSV，UTF-8 带 BOM，Excel 直接打开不乱码）。
     *
     * <p>筛选条件与列表接口一致（vin / mediaStatus / hours），行数上限
     * {@value EventService#EXPORT_MAX_ROWS}；导出动作只读、无副作用，可重复点击。
     */
    @GetMapping("/accidents/export")
    public ResponseEntity<byte[]> exportAccidents(
            @RequestParam(required = false) String vin,
            @RequestParam(required = false) AccidentEvent.MediaStatus mediaStatus,
            @RequestParam(defaultValue = "168") int hours) {
        long now = System.currentTimeMillis();
        long from = now - Duration.ofHours(Math.min(Math.max(hours, 1), 2160)).toMillis();
        List<AccidentEvent> rows = eventService.listAccidentsForExport(vin, mediaStatus,
                TimeUtils.toInstant(from), TimeUtils.toInstant(now));

        CsvBuilder csv = CsvBuilder.of(
                "事件ID", "VIN", "企业ID", "发生时间", "事故描述", "纬度", "经度",
                "取证状态", "已收媒体数", "应收媒体数");
        for (AccidentEvent e : rows) {
            csv.row(
                    e.getEventId(), e.getVin(), e.getEnterpriseId(),
                    TimeUtils.format(TimeUtils.toEpochMillis(e.getOccurredAt())), e.getAccidentDesc(),
                    e.getLatitude(), e.getLongitude(),
                    mediaStatusLabel(e.getMediaStatus()),
                    e.getMediaCount(), e.getMediaExpectedCount());
        }
        return csvResponse(csv, "accidents");
    }

    /** 故障清单导出（CSV），约束同事故导出。 */
    @GetMapping("/faults/export")
    public ResponseEntity<byte[]> exportFaults(
            @RequestParam(required = false) String vin,
            @RequestParam(required = false) Integer minSeverity,
            @RequestParam(defaultValue = "168") int hours) {
        long now = System.currentTimeMillis();
        long from = now - Duration.ofHours(Math.min(Math.max(hours, 1), 2160)).toMillis();
        List<FaultRecord> rows = eventService.listFaultsForExport(vin, minSeverity,
                TimeUtils.toInstant(from), TimeUtils.toInstant(now));

        CsvBuilder csv = CsvBuilder.of(
                "记录ID", "VIN", "上报时间", "故障项数", "最高严重等级", "纬度", "经度");
        for (FaultRecord r : rows) {
            csv.row(
                    r.getId(), r.getVin(),
                    TimeUtils.format(TimeUtils.toEpochMillis(r.getReportedAt())),
                    r.getErrorNum(), r.getMaxSeverity(), r.getLatitude(), r.getLongitude());
        }
        return csvResponse(csv, "faults");
    }

    /** 取证状态中文标签（导出面向运营/监管人员，用业务语言而非枚举名）。 */
    private static String mediaStatusLabel(AccidentEvent.MediaStatus status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case REPORTED -> "已上报待取证";
            case MEDIA_REQUESTED -> "已请求媒体";
            case MEDIA_ACCEPTED -> "车端已受理";
            case MEDIA_ARCHIVED -> "已归档";
            case MEDIA_FAILED -> "取证失败";
        };
    }

    /**
     * 组装 CSV 下载响应。
     *
     * <p>文件名保持 ASCII（避免各浏览器对 Content-Disposition 编码处理的差异）；
     * 时间戳精确到秒，同秒内重复导出会覆盖同名文件 —— 对导出场景这是合理语义。
     */
    private static ResponseEntity<byte[]> csvResponse(CsvBuilder csv, String prefix) {
        String timestamp = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String filename = URLEncoder.encode(prefix + "-" + timestamp + ".csv", StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(csv.toCsvBytes());
    }
}
