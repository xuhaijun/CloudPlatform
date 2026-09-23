package com.genvict.dssad.cloud.mqtt.dto;

import java.util.List;

/**
 * 车云交互上行报文（第 5 章）的 Java 映射。
 *
 * <p>采用「同域嵌套 record」组织方式：这些 record 体积极小（3~10 个字段）且<b>仅用于协议映射</b>，
 * 集中在一个文件便于与接口文档逐条比对，避免 14 个碎文件增加阅读成本。
 *
 * <p>全部字段类型使用包装类型（{@code Integer}/{@code Double}/{@code Long}）而非基本类型：
 * 车端数据可能因传感器缺省而上报 {@code null}，基本类型会抛
 * {@code NullPointerException} 导致整条报文被判为「消息有误」。
 */
public final class CarUpMessages {

    private CarUpMessages() {
    }

    /** 车辆准静态参数信息（5.1.4.2.1，Topic：{@code .../inh/up}，QoS 1）。 */
    public record Inh(
            String vin,
            String msgId,
            Long timestamp,
            /** 自动驾驶硬件版本，如 Orin 域控型号。 */
            String hwVersion,
            /** 自动驾驶系统软件版本号。 */
            String adVersion,
            /** 蜂窝通讯类型：0 未知 / 1 4G / 2 5G / 3 5GA / 4 6G。 */
            String comType,
            /** 时间同步方式：0 无法获取 / 1 不支持 / 2 NTP / 3 GNSS / 4 GNSS+NTP / 5 其他。 */
            String timeSync) {
    }

    /** 车辆低频运行状态信息（5.1.4.3.1，Topic：{@code .../low-state/up}，QoS 0，60s/次）。 */
    public record LowState(
            String vin,
            String msgId,
            Long timestamp,
            /** 定位状态 0~13。 */
            String gnssStatus,
            /** 实时总质量 kg，0 表示未知。 */
            Double vehicleTotalWeight,
            /** 胎压 kPa，顺序：从车头开始从左到右。 */
            List<Double> tirePressure,
            /** 预计续航 km。 */
            Double endurance,
            /** 里程表读数 km。 */
            Double mileage,
            /** 电池剩余电量 %。 */
            Double soc,
            /** 电池容量（度）。 */
            Double batteryCapacity,
            /** 电池温度，数据偏移量 100，即上报值 - 100 = 实际摄氏度。 */
            Integer temperature,
            /** 充电状态：0 缺省 / 1 未充电 / 3 正在充电 / 4 充电故障。 */
            String chargeState) {

        /**
         * 电池温度（实际摄氏度）。
         *
         * <p>协议为节省带宽，{@code temperature} 上报的是「实际摄氏度 + 100」，
         * 消费者必须自行减 100。把这一步留给每个调用方是隐患 ——
         * 只要有人漏减，大屏就会显示「电池温度 135℃」这种明显失真但不易察觉的数据。
         * 因此这里提供一个<b>名字明确</b>的派生字段：{@code temperature} 保持协议原文，
         * {@code temperatureCelsius} 给出真值，两者同时出现在 JSON 响应里，调用方不可能搞错。
         */
        @com.fasterxml.jackson.annotation.JsonProperty("temperatureCelsius")
        public Integer temperatureCelsius() {
            return temperature == null ? null : temperature - 100;
        }
    }

    /** 车辆低频基础信息（5.1.4.3.2，Topic：{@code .../low-base/up}，QoS 0，1s/次）。 */
    public record LowBase(
            String vin,
            String msgId,
            Long timestamp,
            /** 经度，东经为正（GCJ-02）。 */
            Double longitude,
            /** 纬度，北纬为正（GCJ-02）。 */
            Double latitude,
            /** 高程 m。 */
            Double altitude,
            /** 速度 km/h，保留 2 位小数。 */
            Double speed,
            /** 加速度，单位 0.01 m/s²。 */
            Double accSpeed,
            /** 驾驶模式：0 自动驾驶 / 1 人工驾驶 / 2 远程接管。 */
            Integer drivingMode,
            /** 航向角，正北为 0 度顺时针，精度 0.01 度。 */
            Double heading) {
    }

    /** 车辆事故信息（5.1.4.4.1，Topic：{@code .../accident/up}，QoS 1）。 */
    public record Accident(
            String vin,
            String msgId,
            Long timestamp,
            /** 车端生成的事故 ID，后续媒体请求以此关联。 */
            String eventId,
            String accidentDesc,
            /** 事发位置，数组第一位是纬度，第二位是经度。 */
            List<Double> currentPoint) {
    }

    /** 单条故障明细（5.1.4.4.2 的 {@code errorData} 元素）。 */
    public record FaultItem(
            /** 故障码，取值见附录 A.1。 */
            String errorCode,
            /** 故障发生时间（毫秒时间戳）。 */
            Long errorTimestamp,
            /** 严重等级 0~7：0~1 无影响 / 2~3 可恢复 / 4~5 需人工处理 / 6~7 严重不可恢复。 */
            String errorSeverity,
            /** 同一故障类别的累计上报次数，由车端自计量。 */
            Integer msgcount,
            String errorReason) {
    }

    /** 车辆故障信息（5.1.4.4.2，Topic：{@code .../error-report/up}，QoS 1）。 */
    public record FaultReport(
            String vin,
            String msgId,
            Long timestamp,
            Integer errorNum,
            List<FaultItem> errorData,
            /** 当前坐标点，第一位纬度、第二位经度。 */
            List<Double> currentPoint) {
    }

    /**
     * 车端事故媒体数据请求回复（5.1.4.1.1，Topic：{@code .../accident-media-resp/up}，QoS 1）。
     *
     * <p>车端收到 {@code accident-media/down} 后的应答，表示「已受理并即将通过 HTTP 上传视频」。
     * 该报文本身是 ACK，故其 {@code ackMsgId} 对应下行请求的 {@code msgId}。
     */
    public record AccidentMediaResp(
            String vin,
            String msgId,
            String ackMsgId,
            Long timestamp,
            String res) {

        /** 是否受理成功。 */
        public boolean accepted() {
            return "1".equals(res);
        }
    }
}
