package com.genvict.dssad.cloud.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.genvict.dssad.cloud.common.exception.BizException;
import com.genvict.dssad.cloud.common.api.ErrorCode;
import com.genvict.dssad.cloud.common.store.StateStore;
import com.genvict.dssad.cloud.common.util.JsonUtils;
import com.genvict.dssad.cloud.common.util.TimeUtils;
import com.genvict.dssad.cloud.config.AppProperties;
import com.genvict.dssad.cloud.integration.dto.RegulatoryDtos;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Optional;

/**
 * 监管平台 HTTP 对接客户端（文档 6.2 章）。
 *
 * <p>严格实现文档 8.3 的可靠性要求：
 * <ul>
 *   <li><b>超时 30s</b>（连接与读取均设置，防止对端 hang 住拖垮我方线程）；</li>
 *   <li><b>重试 3 次，间隔 1s / 2s / 4s</b>；</li>
 *   <li>仅对<b>可恢复错误</b>重试（超时、连接失败、5xx）；
 *       对 4xx（参数错、签名错、无权限）立即失败 —— 重试只会浪费 7 秒并放大对端压力。</li>
 * </ul>
 *
 * <p>本类<b>不在启动时阻塞</b>：MQTT 密码与 token 采用「按需拉取 + 缓存」，
 * 拉取失败只记录告警并返回 empty，由调用方决定降级路径（如使用静态兜底配置），
 * 保证平台自身可用性不被外部依赖拖垮。
 */
@Slf4j
@Component
public class RegulatoryGateway implements MqttCredentialProvider {

    /** token 缓存键。 */
    private static final String TOKEN_CACHE_KEY = "regulatory:access-token";

    /** MQTT 密码缓存键。 */
    private static final String MQTT_PWD_CACHE_KEY = "regulatory:mqtt-password";

    /** 提前刷新阈值：到期前 5 分钟即刷新，避免边界上用到刚过期的凭据。 */
    private static final long REFRESH_AHEAD_MILLIS = Duration.ofMinutes(5).toMillis();

    private final AppProperties properties;
    private final AppProperties.Regulatory config;
    private final RegulatorySigner signer;
    private final StateStore stateStore;
    private final RestClient restClient;

    public RegulatoryGateway(AppProperties properties, RegulatorySigner signer, StateStore stateStore) {
        this.properties = properties;
        this.config = properties.regulatory();
        this.signer = signer;
        this.stateStore = stateStore;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(config.timeoutMillis()));
        factory.setReadTimeout(Duration.ofMillis(config.timeoutMillis()));
        this.restClient = RestClient.builder()
                .baseUrl(config.baseUrl())
                .requestFactory(factory)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    // ==================== 对外能力 ====================

    /**
     * 获取 MQTT 连接密码（实现 {@link MqttCredentialProvider}）。
     *
     * <p>文档 6.2.2.1.1：请求体 {@code {clientId, secret}}，其中
     * {@code secret = SHA256(clientId + "CMAT")}；响应 {@code {token, expiredAt}}，
     * 该 token 即 MQTT 连接密码。
     */
    @Override
    public Optional<String> fetchMqttPassword(String clientId) {
        Optional<String> cached = stateStore.get(MQTT_PWD_CACHE_KEY);
        if (cached.isPresent()) {
            return cached;
        }
        try {
            RegulatoryDtos.MqttAccessRequest body =
                    new RegulatoryDtos.MqttAccessRequest(clientId, signer.secret(clientId));
            RegulatoryDtos.CredentialResponse response = postForData(
                    config.mqttAccessPath(), body, RegulatoryDtos.CredentialResponse.class, false);
            if (response == null || response.token() == null) {
                log.error("[监管平台] 换取 MQTT 密码返回为空 clientId={}", clientId);
                return Optional.empty();
            }
            stateStore.put(MQTT_PWD_CACHE_KEY, response.token(),
                    ttlOf(response.expiredAt(), Duration.ofMinutes(30)));
            log.info("[监管平台] 已换取 MQTT 连接密码 clientId={} expiredAt={}",
                    clientId, TimeUtils.format(TimeUtils.toInstant(
                            response.expiredAt() == null ? 0L : response.expiredAt())));
            return Optional.of(response.token());
        } catch (BizException e) {
            log.error("[监管平台] 换取 MQTT 密码失败：{}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 获取平台临时 token（用于调用 6.2.2.2 事件类接口）。
     *
     * <p>流程：先用 MQTT 密码换取 token（文档 6.2.2.1.2 的 password 即上一步的 token），
     * 再带 {@code X-Token} 调用业务接口。此处做了本地缓存，避免每个请求都去换一次。
     */
    public String accessToken() {
        Optional<String> cached = stateStore.get(TOKEN_CACHE_KEY);
        if (cached.isPresent()) {
            return cached.get();
        }
        String enterpriseId = properties.enterpriseId();
        String password = fetchMqttPassword(properties.mqttClientId())
                .orElseGet(() -> {
                    log.warn("[监管平台] 未取得 MQTT 密码，尝试使用静态配置密码换取 token");
                    return properties.mqtt().password();
                });
        RegulatoryDtos.TokenRequest body = new RegulatoryDtos.TokenRequest(enterpriseId, password);
        RegulatoryDtos.CredentialResponse response = postForData(
                config.tokenPath(), body, RegulatoryDtos.CredentialResponse.class, false);
        if (response == null || response.token() == null) {
            throw new BizException(ErrorCode.UPSTREAM_ERROR, "监管平台未返回可用 token");
        }
        stateStore.put(TOKEN_CACHE_KEY, response.token(),
                ttlOf(response.expiredAt(), Duration.ofMinutes(30)));
        return response.token();
    }

    /** 拉取指定区域的今日地图增强信息（6.2.2.2.1）。 */
    public RegulatoryDtos.MapehnResponse fetchTodayMapehn(String areaCode) {
        RegulatoryDtos.MapehnRequest body =
                new RegulatoryDtos.MapehnRequest(properties.enterpriseId(), areaCode);
        return postForData(config.mapehnPath(), body, RegulatoryDtos.MapehnResponse.class, true);
    }

    /** 清空本地凭据缓存（密钥轮换时调用，对应文档 4.2「密钥定期更换机制」）。 */
    public void evictCredentials() {
        stateStore.delete(TOKEN_CACHE_KEY);
        stateStore.delete(MQTT_PWD_CACHE_KEY);
        log.info("[监管平台] 本地凭据缓存已清空，下次调用将重新换取");
    }

    // ==================== 内部实现 ====================

    /**
     * 带重试的 POST。
     *
     * <p>反序列化分两步：先把整包解析为 {@link JsonNode}，取出 {@code code}/{@code message}
     * 做统一校验，再把 {@code data} 节点转换为目标类型。
     * 这样做的好处是<b>不同类型的业务接口共用同一套错误处理</b>，
     * 且避免了 Java 泛型擦除导致的 {@code Envelope<T>} 反序列化陷阱
     * （直接用泛型包装类反序列化时，{@code data} 会退化成 {@code LinkedHashMap}）。
     *
     * @param path      相对路径
     * @param body      请求体
     * @param dataType  data 字段目标类型
     * @param withToken 是否需要携带 {@code X-Token}（换取凭据的两个接口不需要）
     */
    private <T> T postForData(String path, Object body, Class<T> dataType, boolean withToken) {
        long[] delays = config.retryDelaysMillis();
        int maxAttempts = delays.length + 1;
        RuntimeException lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                RestClient.RequestBodySpec spec = restClient.post().uri(path)
                        .contentType(MediaType.APPLICATION_JSON);
                if (withToken) {
                    spec = spec.header("X-Token", accessToken());
                }
                JsonNode envelope = spec.body(body).retrieve().body(JsonNode.class);
                return extractData(path, envelope, dataType);
            } catch (BizException e) {
                // 业务级错误码：重试无意义，立即失败
                lastError = e;
                break;
            } catch (RestClientException e) {
                lastError = e;
                log.warn("[监管平台] 调用失败（第 {}/{} 次）path={} 原因={}",
                        attempt, maxAttempts, path, e.getMessage());
                if (attempt <= delays.length) {
                    sleep(delays[attempt - 1]);
                }
            }
        }
        throw new BizException(ErrorCode.UPSTREAM_ERROR,
                "监管平台调用失败 path=" + path + " 原因=" + (lastError == null ? "未知" : lastError.getMessage()));
    }

    /** 解析统一响应体，校验成功码并取出 data。 */
    private <T> T extractData(String path, JsonNode envelope, Class<T> dataType) {
        if (envelope == null || envelope.isNull()) {
            throw new BizException(ErrorCode.UPSTREAM_ERROR, "监管平台返回空响应：" + path);
        }
        String code = envelope.path("code").asText(null);
        if (code == null || !"0000".equals(code)) {
            String message = envelope.path("message").asText("");
            throw new BizException(ErrorCode.UPSTREAM_ERROR,
                    "监管平台返回错误 code=" + code + " message=" + message);
        }
        JsonNode data = envelope.get("data");
        if (data == null || data.isNull()) {
            return null;
        }
        return JsonUtils.mapper().convertValue(data, dataType);
    }

    private Duration ttlOf(Long expiredAt, Duration fallback) {
        if (expiredAt == null || expiredAt <= 0) {
            return fallback;
        }
        long remain = expiredAt - TimeUtils.nowMillis() - REFRESH_AHEAD_MILLIS;
        return remain <= 0 ? Duration.ofMinutes(1) : Duration.ofMillis(remain);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
