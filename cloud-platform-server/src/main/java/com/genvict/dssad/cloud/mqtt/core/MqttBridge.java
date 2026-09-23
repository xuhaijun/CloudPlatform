package com.genvict.dssad.cloud.mqtt.core;

import com.genvict.dssad.cloud.mqtt.model.MqttConnectionState;

/**
 * MQTT 桥接能力（连接状态 + 离线补传），供运维侧与调度组件使用。
 *
 * <p>与 {@link MqttPublisher} 分离的原因：业务代码（Service / Handler）只需要「发消息」，
 * 不应看到「连接状态」「补传」等基础设施细节；而监控端点与重发调度需要这些能力。
 * 接口隔离后，业务类的依赖面更窄、单测更好写。
 */
public interface MqttBridge extends MqttPublisher {

    /** 当前连接状态。 */
    MqttConnectionState state();

    /**
     * 补传离线队列中的报文。
     *
     * @param maxCount 单次最大条数
     * @return 实际补传成功条数
     */
    int flushOffline(int maxCount);

    /** 已成功发布的报文总数。 */
    long publishedCount();

    /** 发布失败累计次数。 */
    long publishFailedCount();

    /** 已发生的重连尝试次数。 */
    int reconnectAttempts();
}
