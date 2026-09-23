package com.genvict.dssad.cloud.service;

import com.genvict.dssad.cloud.common.api.ErrorCode;
import com.genvict.dssad.cloud.common.exception.BizException;
import com.genvict.dssad.cloud.config.AppProperties;
import com.genvict.dssad.cloud.domain.entity.AccidentEvent;
import com.genvict.dssad.cloud.domain.entity.MediaAsset;
import com.genvict.dssad.cloud.domain.repository.AccidentEventRepository;
import com.genvict.dssad.cloud.domain.repository.MediaAssetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * 事故媒体归集服务（对应接口文档 5.2.2.2.1「车端事故媒体上传」）。
 *
 * <p><b>背景与定位</b>：文档把该接口放在「5.2 平台云端HTTP 接口」下，即
 * 车端 → 监管平台。但在<b>通道 B（企业自建网关）</b>部署形态下，车端只与企业云端通信，
 * 企业云端必须能接收事故视频，否则 {@code accident-media/down} 请求下发后
 * 视频永远无法回传，事故取证链断裂。因此本平台按文档<b>完全相同的 Path 与字段</b>
 * 实现该上传接口，车端无需为两种形态做差异化适配。
 *
 * <p><b>鉴权方式（关键设计）</b>：该接口是 {@code multipart/form-data}，
 * body 是可达 200MB 的二进制文件，无法用 HMAC 签名保护（把文件读进内存算摘要会
 * 造成严重的内存与延迟问题）。因此改用<b>业务态鉴权</b>：车端必须回传平台下发
 * {@code accident-media/down} 时使用的 {@code msgId}（即本请求的 {@code ackMsgId}），
 * 且该值必须与事故事件记录的下发编号一致 ——
 * <b>车端只能对平台已经请求过的那次事件、那个方向上传视频</b>，
 * 无法伪造上传任意视频来污染证据链。
 *
 * <p><b>幂等</b>：同一 {@code eventId + cameraDirection} 重复上传时，
 * 直接返回已归档的记录（车端网络抖动重试属常态），不重复计数、不重复落盘。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccidentMediaService {

    /** 事故媒体统一按 mp4 归档（文档 6.3.2.1.2 响应 type 定义为 1 = mp4）。 */
    private static final String MEDIA_TYPE = "mp4";

    /** 本地存储的类别目录名，用于 {root}/{kind}/... 的目录划分与按类别清理。 */
    private static final String KIND_ACCIDENT = "accident";

    private final AccidentEventRepository accidentRepository;
    private final MediaAssetRepository mediaAssetRepository;
    private final MediaService mediaService;
    private final EventService eventService;
    private final AppProperties properties;

    /**
     * 接收一段事故视频。
     *
     * @param vin             车辆 VIN
     * @param ackMsgId        平台下发 {@code accident-media/down} 时的消息编号（授权的核心凭据）
     * @param eventId         事故 ID
     * @param cameraDirection 摄像头方向：1 前 / 2 右 / 3 后 / 4 左
     * @param originalFilename 原始文件名（仅用于推断扩展名，不参与路径拼接）
     * @param content         文件字节
     * @return 归档后的媒体资产
     */
    @Transactional
    public MediaAsset ingest(String vin, String ackMsgId, String eventId, String cameraDirection,
                            String originalFilename, byte[] content) {
        requireText(vin, "vin");
        requireText(ackMsgId, "ackMsgId");
        requireText(eventId, "eventId");
        requireText(cameraDirection, "cameraDirection");
        if (MediaService.directionName(cameraDirection).isEmpty()) {
            throw BizException.paramInvalid("非法的摄像头方向：" + cameraDirection);
        }

        AccidentEvent event = accidentRepository.findByEventId(eventId)
                .orElseThrow(() -> BizException.notFound("事故事件不存在：" + eventId));

        // ---- 授权校验：必须是平台下发过请求的那次事件 ----
        if (!StringUtils.hasText(event.getMediaRequestMsgId())) {
            throw new BizException(ErrorCode.UNAUTHORIZED,
                    "该事故尚未下发媒体请求，拒绝接收上传 eventId=" + eventId);
        }
        if (!event.getMediaRequestMsgId().equals(ackMsgId)) {
            log.warn("[事故媒体] ackMsgId 与已下发的请求编号不匹配 eventId={} 期望={} 实际={}",
                    eventId, event.getMediaRequestMsgId(), ackMsgId);
            throw new BizException(ErrorCode.UNAUTHORIZED, "ackMsgId 与平台下发的媒体请求不匹配");
        }
        if (!event.getVin().equals(vin)) {
            throw new BizException(ErrorCode.UNAUTHORIZED,
                    "vin 与事故事件所属车辆不一致 eventId=" + eventId);
        }
        if (event.getMediaStatus() == AccidentEvent.MediaStatus.MEDIA_FAILED) {
            throw BizException.paramInvalid("车端已拒绝该媒体请求，不再接收上传 eventId=" + eventId);
        }

        // ---- 幂等：同方向重复上传直接返回既有记录 ----
        Optional<MediaAsset> existing =
                mediaAssetRepository.findFirstByEventIdAndCameraDirection(eventId, cameraDirection);
        if (existing.isPresent()) {
            log.info("[事故媒体] 重复上传已忽略 eventId={} 方向={} 既有文件={}",
                    eventId, cameraDirection, existing.get().getStoragePath());
            return existing.get();
        }

        // ---- 落盘 + 建档 ----
        MediaService.StoredFile stored = mediaService.storeLocal(vin, cameraDirection, KIND_ACCIDENT,
                originalFilename, content, properties.storage().maxUploadBytes());
        MediaAsset asset = eventService.archiveAccidentMedia(eventId, cameraDirection,
                stored.storagePath(), stored.url(), stored.fileSize(), MEDIA_TYPE);

        log.info("[事故媒体] 已归档 eventId={} vin={} 方向={} 大小={}字节 路径={}",
                eventId, vin, cameraDirection, content.length, stored.storagePath());
        return asset;
    }

    private void requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw BizException.paramInvalid("缺少必填字段：" + field);
        }
    }
}
