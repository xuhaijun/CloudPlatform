package com.genvict.dssad.cloud.mqtt.handler;

import com.genvict.dssad.cloud.mqtt.model.MqttType;

import java.util.Set;

/**
 * MQTT 报文处理器。
 *
 * <p>接口设计用 {@link #supportedTypes()} 返回集合而不是单个类型，是为了让
 * 「一类报文共用一段处理逻辑」的场景（如 6 种 {@code *-resp} 回复报文的处理完全一致）
 * 只写一个实现类，避免 6 个几乎相同的类。
 *
 * <p>约定：
 * <ul>
 *   <li>处理器<b>只关心业务</b>，不负责 ACK 回复与留痕 —— 这两件事由
 *       {@code MqttMessageRouter} 统一做，保证所有类型行为一致；</li>
 *   <li>校验失败或业务失败时抛 {@link com.genvict.dssad.cloud.common.exception.BizException}，
 *       Router 会据此回复 {@code res=2}（消息有误）；</li>
 *   <li>实现必须是<b>线程安全</b>的：报文由多线程消费（见 {@code MqttInboundWorker}），
 *       处理器本身应为无状态（依赖注入的都是无状态 Service）。</li>
 * </ul>
 */
public interface MessageHandler {

    /** 本处理器支持的报文类型。 */
    Set<MqttType> supportedTypes();

    /** 处理报文。 */
    void handle(MessageContext context);
}
