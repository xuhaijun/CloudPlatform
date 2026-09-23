package com.genvict.dssad.cloud.web;

import com.genvict.dssad.cloud.common.api.ApiResponse;
import com.genvict.dssad.cloud.common.exception.BizException;
import com.genvict.dssad.cloud.common.util.TimeUtils;
import com.genvict.dssad.cloud.config.AppProperties;
import com.genvict.dssad.cloud.service.MediaService;
import com.genvict.dssad.cloud.service.RemoteCockpitService;
import com.genvict.dssad.cloud.web.dto.EnterpriseApiDtos;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 企业云端 HTTP 接口（文档 6.3 章）—— <b>对外合规接口，由监管平台调用</b>。
 *
 * <p>契约要点：
 * <ul>
 *   <li>Base Path：{@code /enterprise/api/v1}（对应文档 6.3.1）；</li>
 *   <li>统一响应体 {@code {code, message, data}}，成功码 {@code 0000}（文档 7.1）；</li>
 *   <li>三个接口全部为 {@code POST} + {@code application/json}；</li>
 *   <li>鉴权由 {@link com.genvict.dssad.cloud.security.EnterpriseSignatureFilter} 统一完成
 *       （文档 6.3.1 允许各企业自定认证方式，本平台采用 HMAC 签名 + 时间窗 + Nonce 防重放）。</li>
 * </ul>
 *
 * <p>本控制器只做「参数校验 → 调用服务 → 组装响应」，不含任何业务逻辑，
 * 便于被 {@code @WebMvcTest} 切片测试直接覆盖契约。
 */
@Slf4j
@RestController
@RequestMapping("/enterprise/api/v1")
@RequiredArgsConstructor
public class EnterpriseApiController {

    private final MediaService mediaService;
    private final RemoteCockpitService remoteCockpitService;

    /**
     * 实时视频地址获取（文档 6.3.2.1.1）。
     *
     * <p>返回车端各方向实时视频的<b>拉流地址</b>（SRS 提供 HLS/HTTP-FLV）。
     */
    @PostMapping("/video/live")
    public ApiResponse<EnterpriseApiDtos.LiveVideoResponse> liveVideo(
            @Valid @RequestBody EnterpriseApiDtos.LiveVideoRequest request) {
        log.info("[对外接口] 实时视频请求 vin={} 方向={} 类型={} msgId={}",
                request.vin(), request.cameraDirection(), request.type(), request.msgId());
        List<MediaService.LiveVideo> videos =
                mediaService.liveVideo(request.vin(), request.cameraDirection(), request.type());
        List<EnterpriseApiDtos.VideoItem> items = videos.stream()
                .map(video -> new EnterpriseApiDtos.VideoItem(
                        video.cameraDirection(), video.url(), video.type()))
                .toList();
        return ApiResponse.ok(new EnterpriseApiDtos.LiveVideoResponse(request.vin(), items));
    }

    /**
     * 历史视频获取（文档 6.3.2.1.2）。
     *
     * <p>严格按文档要求的 {@code yyyy-MM-dd HH:mm:ss} 文本解析，并校验单次跨度上限
     * （默认 168 小时），防止一次请求扫描整月录像。
     */
    @PostMapping("/video/history")
    public ApiResponse<EnterpriseApiDtos.HistoryVideoResponse> historyVideo(
            @Valid @RequestBody EnterpriseApiDtos.HistoryVideoRequest request) {
        long startMillis = parseTimeParam("startTime", request.startTime());
        long endMillis = parseTimeParam("endTime", request.endTime());
        log.info("[对外接口] 历史视频请求 vin={} 方向={} [{} ~ {}] msgId={}",
                request.vin(), request.cameraDirection(), request.startTime(), request.endTime(), request.msgId());

        List<MediaService.HistoryVideo> videos =
                mediaService.historyVideo(request.vin(), request.cameraDirection(), startMillis, endMillis);
        List<EnterpriseApiDtos.HistoryItem> items = videos.stream()
                .map(video -> new EnterpriseApiDtos.HistoryItem(
                        video.cameraDirection(), video.url(), video.type(),
                        video.startTime(), video.endTime()))
                .toList();
        return ApiResponse.ok(new EnterpriseApiDtos.HistoryVideoResponse(request.vin(), items));
    }

    /**
     * 远程驾驶接管发起（文档 6.3.2.2.1）。
     *
     * <p>监管平台/交巡警发起接管后，企业云端指派远驾舱并同步上报监管平台
     * （{@code remote-driving/up}，type=1），响应返回舱与驾驶员信息。
     */
    @PostMapping("/event/remote-cockpit-start")
    public ApiResponse<EnterpriseApiDtos.RemoteCockpitStartResponse> remoteCockpitStart(
            @Valid @RequestBody EnterpriseApiDtos.RemoteCockpitStartRequest request) {
        remoteCockpitService.validateStarter(request.starter());
        log.warn("[对外接口] 远程驾驶接管发起 vin={} 发起人={}", request.vin(), request.starter());

        AppProperties.RemoteDriving.Cockpit cockpit =
                remoteCockpitService.startTakeover(request.vin(), request.starter());
        remoteCockpitService.bindVehicle(request.vin(), cockpit.cockpitId());

        return ApiResponse.ok(new EnterpriseApiDtos.RemoteCockpitStartResponse(
                request.vin(), cockpit.cockpitId(), cockpit.driver(),
                cockpit.identityNumber(), cockpit.driverLicenseNumber(), cockpit.mobile()));
    }

    /**
     * 结束远程驾驶接管（平台内部/企业侧调用，用于把 type=2 上报给监管平台）。
     *
     * <p>文档 6.3 未定义该接口，属于企业侧补充能力：监管平台发起接管后，
     * 企业侧需要有明确的「结束接管」动作，否则监管侧看到的接管状态永远处于进行中。
     */
    @PostMapping("/event/remote-cockpit-end")
    public ApiResponse<Boolean> remoteCockpitEnd(@Valid @RequestBody EnterpriseApiDtos.RemoteCockpitStartRequest request) {
        boolean existed = remoteCockpitService.endTakeover(request.vin());
        return ApiResponse.ok(existed);
    }

    /**
     * 解析历史视频的时间入参，并把格式错误转成「参数不合法」。
     *
     * <p>为什么不能直接调 {@link TimeUtils#parseFlexible}：它抛的是
     * {@link IllegalArgumentException}，会落到全局兜底处理器变成 {@code 9999 系统内部错误}。
     * 对监管平台而言，这属于「我传错了参数」而非「对方系统故障」——错误码语义错了会导致
     * 对端按 {@code 8.3 HTTP 请求重试} 反复重试同一个不可能成功的请求。
     * 这里显式转换，把错误码与字段名一并回传，方便联调定位。
     */
    private static long parseTimeParam(String field, String text) {
        try {
            return TimeUtils.parseFlexible(text);
        } catch (RuntimeException e) {
            throw BizException.paramInvalid(
                    field + " 格式不合法，要求 yyyy-MM-dd HH:mm:ss，实际值：" + text);
        }
    }
}
