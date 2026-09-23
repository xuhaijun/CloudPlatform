package com.genvict.dssad.cloud.integration;

import java.util.Optional;

/**
 * MQTT 连接密码提供者。
 *
 * <p>文档 5.1.2 / 6.1.2 规定 password 需通过 HTTP 接口动态换取
 * （{@code /passport/api/v1/auth/access-enterprise-mqtt}），而不是静态配置。
 * 抽取为接口的目的：让 MQTT 客户端管理器不直接依赖 HTTP 客户端实现，
 * 从而在单元测试中可注入桩实现，也不需要真实网络即可验证连接流程。
 */
public interface MqttCredentialProvider {

    /**
     * 获取指定 clientId 的 MQTT 连接密码。
     *
     * <p>实现须自行处理缓存与过期刷新；失败时返回 {@link Optional#empty()}
     * 而不是抛异常，让连接管理器回落到静态配置密码。
     *
     * @param clientId MQTT clientId（{@code e_{enterpriseId}}）
     */
    Optional<String> fetchMqttPassword(String clientId);
}
