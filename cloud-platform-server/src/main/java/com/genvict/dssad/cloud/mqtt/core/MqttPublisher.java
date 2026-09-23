package com.genvict.dssad.cloud.mqtt.core;

/**
 * MQTT 发布出口。
 *
 * <p>抽象为接口而非直接依赖 Paho：
 * <ol>
 *   <li>业务代码（Service / Handler）只依赖本接口，<b>可单元测试</b>（Mock 即可断言发布内容）；</li>
 *   <li>MQTT 客户端可替换（如换用 MQTT 5.0 客户端、或对接其他合规平台 Broker）而不影响业务层。</li>
 * </ol>
 */
public interface MqttPublisher {

    /**
     * 发布消息。
     *
     * @param topic   目标 Topic（必须由 {@link com.genvict.dssad.cloud.mqtt.topic.TopicBuilder} 构造）
     * @param payload 消息体，非 String 类型自动序列化为 JSON
     * @param qos     QoS 等级（0/1/2）
     * @param retained 是否保留消息
     * @return 是否已成功提交到客户端（注意：返回 true 仅表示本地发送成功，
     *         业务确认仍需依赖对端 ACK）
     */
    boolean publish(String topic, Object payload, int qos, boolean retained);

    /** 发布 JSON 消息（QoS 1、不保留）的便捷方法。 */
    default boolean publish(String topic, Object payload, int qos) {
        return publish(topic, payload, qos, false);
    }

    /** 当前是否已连接。 */
    boolean isConnected();
}
