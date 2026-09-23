package com.genvict.dssad.cloud.service;

import com.genvict.dssad.cloud.common.api.ErrorCode;
import com.genvict.dssad.cloud.common.exception.BizException;
import com.genvict.dssad.cloud.common.util.TimeUtils;
import com.genvict.dssad.cloud.config.AppProperties;
import com.genvict.dssad.cloud.domain.entity.MediaAsset;
import com.genvict.dssad.cloud.domain.repository.MediaAssetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 媒体服务：实时视频拉流地址（文档 6.3.2.1.1）与历史视频查询（6.3.2.1.2）。
 *
 * <p>关键设计：
 * <ol>
 *   <li><b>地址与文件分离</b>：实时视频只返回<b>拉流地址</b>（由 SRS 提供 HLS/HTTP-FLV），
 *       不做转码也不落地文件；历史视频返回<b>已归档文件</b>的下载地址。
 *       两者共用一个响应结构，前端无需区分处理。</li>
 *   <li><b>存储可替换</b>：文件的物理落盘由 {@link #storeLocal} 负责，
 *       生产环境替换为对象存储（MinIO/S3）只需改本类的一个方法，
 *       因为对外契约是「存储路径 + 对外 URL」两个字段（见 {@link MediaAsset}）。</li>
 *   <li><b>区间重叠语义</b>：历史点播的时间区间与录像切片几乎不可能完全对齐，
 *       必须按「重叠」查询，否则用户选 00:00~24:00 却查不到 00:00~01:00 的切片。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaService {

    /** 摄像头方向字典。 */
    private static final Map<String, String> DIRECTION_NAMES = Map.of(
            "1", "前向", "2", "右向", "3", "后向", "4", "左向");

    /** 流类型字典。 */
    private static final Map<String, String> STREAM_TYPES = Map.of(
            "1", "m3u8", "2", "flv");

    private static final DateTimeFormatter DATE_DIR_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final MediaAssetRepository mediaAssetRepository;
    private final AppProperties properties;

    // ==================== 实时视频 ====================

    /**
     * 生成实时视频拉流地址（对应文档 6.3.2.1.1）。
     *
     * @param vin        车辆 VIN
     * @param directions 需要的摄像头方向：1 前 / 2 右 / 3 后 / 4 左
     * @param type       流类型：1 m3u8 / 2 flv
     * @return 每个方向一条拉流地址
     */
    public List<LiveVideo> liveVideo(String vin, List<String> directions, String type) {
        if (!StringUtils.hasText(vin)) {
            throw BizException.paramInvalid("vin 不能为空");
        }
        String streamType = STREAM_TYPES.getOrDefault(type == null ? "1" : type, "m3u8");
        List<String> targets = (directions == null || directions.isEmpty())
                // 文档要求至少覆盖前/右/后/左 4 个方向，未指定时默认全给
                ? List.of("1", "2", "3", "4")
                : directions;

        List<LiveVideo> result = new ArrayList<>(targets.size());
        for (String direction : targets) {
            if (!DIRECTION_NAMES.containsKey(direction)) {
                throw BizException.paramInvalid("非法的摄像头方向：" + direction);
            }
            result.add(new LiveVideo(direction, DIRECTION_NAMES.get(direction),
                    buildLiveUrl(vin, direction, streamType), streamType));
        }
        log.info("[媒体] 生成实时拉流地址 vin={} 方向={} 类型={}", vin, targets, streamType);
        return result;
    }

    /** 按 SRS 地址约定拼接拉流地址：{@code {base}/{app}/{vin}_{direction}.{ext}}。 */
    public String buildLiveUrl(String vin, String direction, String streamType) {
        String base = trimTrailingSlash(properties.srs().httpBaseUrl());
        return base + "/" + properties.srs().app() + "/" + vin + "_" + direction + "." + streamType;
    }

    // ==================== 历史视频 ====================

    /**
     * 历史视频查询（对应文档 6.3.2.1.2）。
     *
     * <p>入参校验遵循文档 8.3「HTTP 请求重试」与平台自我保护原则：
     * 单次查询跨度受限（默认 168 小时 = 7 天），避免一次请求扫描整月录像。
     *
     * @param vin         车辆 VIN
     * @param direction   摄像头方向
     * @param startMillis 开始时间（毫秒）
     * @param endMillis   结束时间（毫秒）
     */
    @Transactional(readOnly = true)
    public List<HistoryVideo> historyVideo(String vin, String direction, long startMillis, long endMillis) {
        if (!StringUtils.hasText(vin)) {
            throw BizException.paramInvalid("vin 不能为空");
        }
        if (!DIRECTION_NAMES.containsKey(direction)) {
            throw BizException.paramInvalid("非法的摄像头方向：" + direction);
        }
        if (endMillis <= startMillis) {
            throw BizException.paramInvalid("结束时间必须晚于开始时间");
        }
        long maxSpanMillis = properties.api().historyVideoMaxSpanHours() * 3600_000L;
        if (endMillis - startMillis > maxSpanMillis) {
            throw BizException.paramInvalid("单次查询时间跨度过大，最大 "
                    + properties.api().historyVideoMaxSpanHours() + " 小时");
        }

        Instant queryStart = TimeUtils.toInstant(startMillis);
        Instant queryEnd = TimeUtils.toInstant(endMillis);
        List<MediaAsset> assets = mediaAssetRepository
                .findByVinAndCameraDirectionAndStartTimeLessThanEqualAndEndTimeGreaterThanEqualOrderByStartTimeAsc(
                        vin, direction, queryEnd, queryStart);

        List<HistoryVideo> result = new ArrayList<>(assets.size());
        for (MediaAsset asset : assets) {
            result.add(new HistoryVideo(
                    asset.getCameraDirection(),
                    DIRECTION_NAMES.get(asset.getCameraDirection()),
                    asset.getUrl(),
                    asset.getMediaType(),
                    TimeUtils.format(asset.getStartTime()),
                    TimeUtils.format(asset.getEndTime()),
                    TimeUtils.toEpochMillis(asset.getStartTime()),
                    TimeUtils.toEpochMillis(asset.getEndTime()),
                    asset.getFileSize()));
        }
        log.info("[媒体] 历史视频查询 vin={} 方向={} [{} ~ {}] 命中 {} 段",
                vin, direction, TimeUtils.format(startMillis), TimeUtils.format(endMillis), result.size());
        return result;
    }

    /**
     * 登记一段历史录像（供企业侧录像同步任务调用）。
     *
     * <p>真实的录像落盘由 NVR/SRS 录制服务完成，本方法只登记元数据与地址，
     * 让文档 6.3.2.1.2 接口有数据可查。
     */
    @Transactional
    public MediaAsset registerHistory(String vin, String direction, long startMillis, long endMillis,
                                     String storagePath, String url, Long fileSize) {
        MediaAsset asset = new MediaAsset();
        asset.setVin(vin);
        asset.setCameraDirection(direction);
        asset.setMediaType("mp4");
        asset.setStoragePath(storagePath);
        asset.setUrl(url);
        asset.setFileSize(fileSize);
        asset.setKind(MediaAsset.Kind.HISTORY.name());
        asset.setStatus(MediaAsset.Status.AVAILABLE.name());
        asset.setUploadedBy("SYSTEM");
        asset.setStartTime(TimeUtils.toInstant(startMillis));
        asset.setEndTime(TimeUtils.toInstant(endMillis));
        return mediaAssetRepository.save(asset);
    }

    // ==================== 本地存储 ====================

    /**
     * 保存上传的文件到本地磁盘，返回「存储路径」。
     *
     * <p>目录结构 {@code {root}/{kind}/{yyyyMMdd}/{vin}/{direction}/{uuid}.{ext}}：
     * 按天分目录便于按日期归档/清理；按 VIN 分目录便于单车导出取证材料。
     *
     * @param maxBytes 文件大小上限（文档 9.2：文件上传 ≤ 200MB）
     * @return 相对于存储根目录的路径，同时附带对外可访问 URL
     */
    public StoredFile storeLocal(String vin, String direction, String kind,
                                 String originalFilename, byte[] content, long maxBytes) {
        if (content == null || content.length == 0) {
            throw BizException.paramInvalid("上传文件内容为空");
        }
        if (content.length > maxBytes) {
            throw new BizException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "文件大小 " + content.length + " 字节，超过上限 " + maxBytes + " 字节");
        }
        String extension = extensionOf(originalFilename);
        String relativePath = String.join("/",
                kind, LocalDate.now().format(DATE_DIR_FORMATTER),
                sanitize(vin), sanitize(direction),
                UUID.randomUUID().toString().replace("-", "") + extension);
        Path target = Paths.get(properties.storage().localRoot()).resolve(relativePath).normalize();
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            // 用带错误码的业务异常包装：对外表现为「媒体存储异常」，不暴露文件系统细节
            throw new BizException(ErrorCode.STORAGE_ERROR, "媒体文件写入失败：" + e.getMessage());
        }
        String url = trimTrailingSlash(properties.storage().publicBaseUrl()) + "/" + relativePath;
        log.info("[媒体] 文件已保存 vin={} 路径={} 大小={}字节", vin, relativePath, content.length);
        return new StoredFile(relativePath, url, (long) content.length);
    }

    private String extensionOf(String filename) {
        if (filename == null) {
            return ".bin";
        }
        int index = filename.lastIndexOf('.');
        if (index < 0 || index == filename.length() - 1) {
            return ".bin";
        }
        String ext = filename.substring(index).toLowerCase();
        // 白名单化扩展名，防止路径穿越或落出可执行文件
        return switch (ext) {
            case ".mp4", ".ts", ".m3u8", ".flv", ".jpg", ".png" -> ext;
            default -> ".bin";
        };
    }

    /** 目录名净化：只保留字母数字和 {@code - _}，防止路径穿越（如 {@code ../../}）。 */
    private String sanitize(String segment) {
        if (segment == null || segment.isBlank()) {
            return "unknown";
        }
        String safe = segment.replaceAll("[^A-Za-z0-9_-]", "");
        return safe.isEmpty() ? "unknown" : safe;
    }

    private String trimTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    // ==================== 返回模型（与文档字段一一对应） ====================

    /**
     * 实时视频地址（文档 6.3.2.1.1 响应 {@code video} 数组元素）。
     *
     * @param cameraDirection 摄像头位置：1 前向 / 2 右向 / 3 后向 / 4 左向
     * @param directionName   方向中文名（平台补充，便于前端直接展示）
     * @param url             拉流地址
     * @param type            视频流类型：m3u8 / flv
     */
    public record LiveVideo(String cameraDirection, String directionName, String url, String type) {
    }

    /**
     * 历史视频（文档 6.3.2.1.2 响应 {@code video} 数组元素）。
     *
     * @param cameraDirection 摄像头位置
     * @param directionName   方向中文名
     * @param url             下载地址
     * @param type            文件类型（mp4）
     * @param startTime       开始时间（东八区文本）
     * @param endTime         结束时间（东八区文本）
     * @param startMillis     开始时间（毫秒，便于前端直接排序/定位）
     * @param endMillis       结束时间（毫秒）
     * @param fileSize        文件大小（字节）
     */
    public record HistoryVideo(String cameraDirection, String directionName, String url, String type,
                              String startTime, String endTime, long startMillis, long endMillis,
                              Long fileSize) {
    }

    /** 本地上传结果。 */
    public record StoredFile(String storagePath, String url, Long fileSize) {
    }

    /** 方向字典（供接口文档与前端字典接口使用）。 */
    public static Map<String, String> directionDictionary() {
        return new LinkedHashMap<>(DIRECTION_NAMES);
    }

    /** 按方向取中文名。 */
    public static Optional<String> directionName(String direction) {
        return Optional.ofNullable(DIRECTION_NAMES.get(direction));
    }
}
