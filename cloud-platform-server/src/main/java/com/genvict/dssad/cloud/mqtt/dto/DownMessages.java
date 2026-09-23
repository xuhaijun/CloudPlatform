package com.genvict.dssad.cloud.mqtt.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * 监管平台下行报文（我方订阅、需要处理）的 Java 映射。
 */
public final class DownMessages {

    private DownMessages() {
    }

    /**
     * 通用回复报文（5.1.5.1.x、6.1.5.1.x）。
     *
     * <p>平台对我方上行的应答。{@code ackMsgId} 即我方发出的 {@code msgId}，
     * 用于关闭重发定时任务；{@code res} 取值见
     * {@link com.genvict.dssad.cloud.mqtt.model.AckCode}。
     */
    public record Ack(
            String enterpriseId,
            String vin,
            String msgId,
            String ackMsgId,
            Long timestamp,
            String res) {
    }

    /**
     * 车端事故媒体数据请求（5.1.5.2.1，Topic：{@code uvodp/{vehicleType}/{vin}/accident-media/down}）。
     *
     * <p>平台收到车端事故上报后，要求车端上传「事发前 15 秒 + 后 5 秒」的多方向视频。
     *
     * <p><b>文档缺陷兼容</b>：该接口的正文字段名为 {@code cameraDirection}，
     * 而数据示例写作 {@code sensorDirection}。为免与任一方联调失败，
     * 这里两个字段都接收，{@link #directions()} 返回有效值。
     */
    public record AccidentMediaRequest(
            String vin,
            String msgId,
            Long timestamp,
            String eventId,
            List<String> cameraDirection,
            List<String> sensorDirection) {

        /** 取摄像头方向集合：优先 {@code cameraDirection}，为空则回落到 {@code sensorDirection}。 */
        @JsonIgnore
        public List<String> directions() {
            if (cameraDirection != null && !cameraDirection.isEmpty()) {
                return cameraDirection;
            }
            return sensorDirection == null ? List.of() : sensorDirection;
        }
    }

    /**
     * 地图增强信息（6.1.5.2.1，Topic：{@code uvodp/{enterpriseId}/mapehn/down}）。
     *
     * <p>交通阻断设施：禁行区域（{@code barrierType=20}）或限速区域（{@code barrierType=21}）。
     * {@code dateType} 与 {@code timeRange} 为生效时间窗约束。
     */
    public record Mapehn(
            String enterpriseId,
            String msgId,
            Long timestamp,
            String areaCode,
            /** 20 禁行区域；21 限速区域。 */
            String barrierType,
            String barrierName,
            String barrierId,
            /** 阻断区域多边形顶点，首尾为同一坐标形成闭环；单点时只有 1 个坐标。 */
            List<List<Double>> barrierPolygon,
            /** 时间段类型：1 工作日 / 2 节假日 / 3 每日 / 4 周六周日。 */
            String dateType,
            /** 时间段集合，如 [["7:00","9:00"],["17:00","19:30"]]。 */
            List<List<String>> timeRange,
            /** 限速值，{@code barrierType=21} 时有效。 */
            Double speedLimit) {
    }
}
