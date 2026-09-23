package com.genvict.dssad.cloud.mqtt.model;

/**
 * Topic 作用域，决定 Topic 的层级格式。
 *
 * <p>对应《数据接口对接文档》附录 B.1：
 * <ul>
 *   <li>{@link #CAR} 车云：{@code uvodp/{vehicleType}/{vin}/{mqttType}/[version/]up|down}</li>
 *   <li>{@link #ENTERPRISE} 云云：{@code uvodp/{enterpriseId}/{mqttType}/up|down}</li>
 * </ul>
 */
public enum TopicScope {

    /** 车云交互（车端 ↔ 平台云端）。 */
    CAR,

    /** 云云交互（企业云端 ↔ 平台云端）。 */
    ENTERPRISE
}
