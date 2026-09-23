package com.genvict.dssad.cloud.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 企业云端对外接口（文档 6.3）请求/响应模型。
 *
 * <p>字段名与文档<b>逐字对齐</b>（含注册平台可能使用的 camelCase 写法），
 * 避免因命名差异在联调时反复扯皮；新增字段只增不改，保持向后兼容。
 */
public final class EnterpriseApiDtos {

    private EnterpriseApiDtos() {
    }

    /**
     * 实时视频地址获取请求（文档 6.3.2.1.1）。
     *
     * @param vin             车辆 VIN
     * @param msgId           消息标识（云端消息编号 UUID）
     * @param cameraDirection 摄像头位置数组：1 前 / 2 右 / 3 后 / 4 左
     * @param type            视频流类型：1 m3u8 / 2 flv
     */
    public record LiveVideoRequest(
            @NotBlank(message = "vin 不能为空") String vin,
            String msgId,
            @NotNull(message = "cameraDirection 不能为空") List<String> cameraDirection,
            String type) {
    }

    /**
     * 历史视频获取请求（文档 6.3.2.1.2）。
     *
     * @param vin             车辆 VIN
     * @param msgId           消息标识
     * @param cameraDirection 摄像头位置（单个方向）
     * @param startTime       开始时间，格式 {@code yyyy-MM-dd HH:mm:ss}
     * @param endTime         结束时间，格式 {@code yyyy-MM-dd HH:mm:ss}
     */
    public record HistoryVideoRequest(
            @NotBlank(message = "vin 不能为空") String vin,
            String msgId,
            @NotBlank(message = "cameraDirection 不能为空") String cameraDirection,
            @NotBlank(message = "startTime 不能为空") String startTime,
            @NotBlank(message = "endTime 不能为空") String endTime) {
    }

    /**
     * 远程驾驶接管发起请求（文档 6.3.2.2.1）。
     *
     * @param vin     车辆 VIN
     * @param starter 发起人：1 交巡警 / 2 监管平台
     */
    public record RemoteCockpitStartRequest(
            @NotBlank(message = "vin 不能为空") String vin,
            @NotBlank(message = "starter 不能为空") String starter) {
    }

    /** 实时视频响应。 */
    public record LiveVideoResponse(String vin, List<VideoItem> video) {
    }

    /** 历史视频响应。 */
    public record HistoryVideoResponse(String vin, List<HistoryItem> video) {
    }

    /**
     * 视频地址项。
     *
     * @param cameraDirection 摄像头位置
     * @param url             地址
     * @param type            类型（m3u8 / flv / mp4）
     */
    public record VideoItem(String cameraDirection, String url, String type) {
    }

    /**
     * 历史视频项。
     *
     * @param cameraDirection 摄像头位置
     * @param url             下载地址
     * @param type            文件类型（mp4）
     * @param startTime       文件开始时间
     * @param endTime         文件结束时间
     */
    public record HistoryItem(String cameraDirection, String url, String type,
                              String startTime, String endTime) {
    }

    /**
     * 远程驾驶接管发起响应。
     *
     * @param vin                 车辆 VIN
     * @param remoteCockpitId     远驾舱 ID
     * @param driver              远驾舱驾驶员姓名
     * @param identityNumber      身份证号
     * @param driverLicenseNumber 驾驶证编号
     * @param mobile              联系方式
     */
    public record RemoteCockpitStartResponse(String vin, String remoteCockpitId, String driver,
                                            String identityNumber, String driverLicenseNumber,
                                            String mobile) {
    }
}
