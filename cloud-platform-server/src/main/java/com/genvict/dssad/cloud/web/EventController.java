package com.genvict.dssad.cloud.web;

import com.genvict.dssad.cloud.common.api.ApiResponse;
import com.genvict.dssad.cloud.common.exception.BizException;
import com.genvict.dssad.cloud.common.model.PageResult;
import com.genvict.dssad.cloud.common.util.TimeUtils;
import com.genvict.dssad.cloud.config.AppProperties;
import com.genvict.dssad.cloud.domain.entity.AccidentEvent;
import com.genvict.dssad.cloud.domain.entity.FaultItem;
import com.genvict.dssad.cloud.domain.entity.FaultRecord;
import com.genvict.dssad.cloud.domain.entity.MediaAsset;
import com.genvict.dssad.cloud.service.EventService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
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
}
