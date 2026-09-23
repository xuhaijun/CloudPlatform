package com.genvict.dssad.cloud.mqtt.model;

/**
 * MQTT 连接状态。
 *
 * <p>用于监控页与告警判定：{@link #RECONNECTING} 持续时间过长（如超过 15 分钟）说明
 * 网络或凭据出现严重问题，需要人工介入（凭据过期是常见原因，见文档 4.2「密钥定期更换机制」）。
 */
public enum MqttConnectionState {

    /** 未启用或已主动断开。 */
    DISCONNECTED,

    /** 正在建立连接。 */
    CONNECTING,

    /** 已连接并完成订阅。 */
    CONNECTED,

    /** 连接丢失，正在按指数退避重连（文档 8.2）。 */
    RECONNECTING,

    /** 凭据获取失败等致命错误，需人工处理。 */
    FAILED
}
