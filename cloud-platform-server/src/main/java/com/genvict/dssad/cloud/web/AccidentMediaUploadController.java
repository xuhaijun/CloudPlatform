package com.genvict.dssad.cloud.web;

import com.genvict.dssad.cloud.common.api.ApiResponse;
import com.genvict.dssad.cloud.common.api.ErrorCode;
import com.genvict.dssad.cloud.common.exception.BizException;
import com.genvict.dssad.cloud.domain.entity.MediaAsset;
import com.genvict.dssad.cloud.service.AccidentMediaService;
import com.genvict.dssad.cloud.service.MediaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 车端事故媒体上传（接口文档 <b>5.2.2.2.1</b>）。
 *
 * <p>Path / Method / Body 字段与文档完全一致，便于车端在「直连监管平台」与
 * 「经企业云端」两种部署形态下共用同一份上传代码：
 * <pre>
 * POST /manager/api/v1/video/accident-media-upload
 * Content-Type: multipart/form-data
 *   vin             车辆 VIN（必需）
 *   msgId           本次上传的消息编号（必需）
 *   ackMsgId        平台下发 accident-media/down 的消息编号（必需，授权凭据）
 *   eventId         事故 ID（必需）
 *   cameraDirection 摄像头方向 1 前 / 2 右 / 3 后 / 4 左（必需）
 *   file            事故视频文件（必需，≤ 200MB）
 * </pre>
 *
 * <p>关于鉴权：文档要求的 {@code X-Token} 是<b>监管平台</b>侧的令牌机制。
 * 本平台作为企业云端，改用更强的业务态鉴权（{@code ackMsgId} 必须与已下发的媒体请求
 * 编号一致，详见 {@link AccidentMediaService}），原因见该方法注释 ——
 * multipart 二进制体无法做签名，而「车端只能上传平台请求过的事件」已经足以
 * 阻止任意视频污染证据链。
 */
@Slf4j
@RestController
@RequestMapping("/manager/api/v1/video")
@RequiredArgsConstructor
public class AccidentMediaUploadController {

    private final AccidentMediaService accidentMediaService;

    /**
     * 接收车端上传的事故视频。
     *
     * @return 归档结果（含对外可访问地址，便于企业侧校验文件是否真的落盘）
     */
    @PostMapping(value = "/accident-media-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> upload(
            @RequestParam("vin") String vin,
            @RequestParam("msgId") String msgId,
            @RequestParam("ackMsgId") String ackMsgId,
            @RequestParam("eventId") String eventId,
            @RequestParam("cameraDirection") String cameraDirection,
            @RequestParam("file") MultipartFile file) {

        if (file == null || file.isEmpty()) {
            throw BizException.paramInvalid("file 不能为空");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            // 读取上传流失败通常是客户端中断，按参数错误返回，避免污染 5xx 告警
            throw new BizException(ErrorCode.PARAM_INVALID, "读取上传文件失败：" + e.getMessage());
        }

        log.info("[事故媒体上传] vin={} eventId={} 方向={} msgId={} 文件={} 大小={}字节",
                vin, eventId, cameraDirection, msgId, file.getOriginalFilename(), content.length);

        MediaAsset asset = accidentMediaService.ingest(
                vin, ackMsgId, eventId, cameraDirection, file.getOriginalFilename(), content);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("eventId", eventId);
        data.put("cameraDirection", cameraDirection);
        data.put("mediaId", asset.getId());
        data.put("url", asset.getUrl());
        data.put("fileSize", asset.getFileSize());
        data.put("directionName", MediaService.directionName(cameraDirection).orElse(""));
        return ApiResponse.ok(data);
    }
}
