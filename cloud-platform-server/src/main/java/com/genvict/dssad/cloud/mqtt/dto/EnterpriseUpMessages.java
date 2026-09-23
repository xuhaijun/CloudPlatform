package com.genvict.dssad.cloud.mqtt.dto;

import java.util.List;

/**
 * 云云交互上行报文（第 6 章）的 Java 映射。
 *
 * <p>与企业云端的实际行为对照：这三个类型是<b>企业云端需要发布到监管平台</b>的业务报文
 * （任务下发、远驾接管、导航路径），{@code MapehnResp} 是对平台下行地图增强信息的应答。
 */
public final class EnterpriseUpMessages {

    private EnterpriseUpMessages() {
    }

    /** 远程驾驶接管信息（6.1.4.2.1，Topic：{@code uvodp/{enterpriseId}/remote-driving/up}）。 */
    public record RemoteDriving(
            String enterpriseId,
            String vin,
            String msgId,
            Long timestamp,
            /** 1 发起接管；2 结束接管。 */
            String type,
            /** 驾驶员姓名（发起接管时必传）。 */
            String driver,
            /** 驾驶证号（发起接管时必传）。 */
            String card,
            /** 台架号 / 远程驾驶舱编号（发起接管时必传）。 */
            String remoteCockpitNo) {
    }

    /** 任务信息（6.1.4.2.2，Topic：{@code uvodp/{enterpriseId}/task/up}）。 */
    public record Task(
            String enterpriseId,
            String vin,
            String msgId,
            Long timestamp,
            /** 1 开始；2 结束。 */
            String taskAction,
            String taskId,
            /** 任务起点（开始任务时必传）。 */
            String taskStartPosition,
            /** 任务起点坐标 [纬度, 经度]。 */
            List<Double> taskStartPoint,
            String taskEndPosition,
            List<Double> taskEndPoint) {
    }

    /** 车端导航路径信息（6.1.4.2.3，Topic：{@code uvodp/{enterpriseId}/groute/up}）。 */
    public record Groute(
            String enterpriseId,
            String vin,
            String msgId,
            Long timestamp,
            /** 导航点集合：首元素为起点，末元素为终点，中间为途经点；每点为 [纬度, 经度]。 */
            List<List<Double>> pathPoints,
            /** 事件发生时位置 [纬度, 经度]。 */
            List<Double> currentPoint) {
    }

    /** 地图增强信息回复（6.1.4.1.1，Topic：{@code uvodp/{enterpriseId}/mapehn-resp/up}）。 */
    public record MapehnResp(
            String enterpriseId,
            String msgId,
            String ackMsgId,
            Long timestamp,
            String res) {
    }
}
