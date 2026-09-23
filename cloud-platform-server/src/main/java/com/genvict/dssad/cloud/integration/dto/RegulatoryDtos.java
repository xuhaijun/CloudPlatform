package com.genvict.dssad.cloud.integration.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 监管平台 HTTP 接口（第 6.2 章）的请求/响应模型。
 */
public final class RegulatoryDtos {

    private RegulatoryDtos() {
    }

    /** 换取 MQTT 连接密钥请求体（6.2.2.1.1）。 */
    public record MqttAccessRequest(String clientId, String secret) {
    }

    /** 换取平台临时 token 请求体（6.2.2.1.2）。 */
    public record TokenRequest(String clientId, String password) {
    }

    /** 密钥 / token 响应体（两个接口结构一致）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CredentialResponse(String token, Long expiredAt) {
    }

    /** 今日地图增强信息请求体（6.2.2.2.1）。 */
    public record MapehnRequest(String enterpriseId, String areaCode) {
    }

    /** 今日地图增强信息响应 data 部分。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MapehnResponse(String areaCode, List<Barrier> barriers) {
    }

    /** 交通阻断项。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Barrier(
            String barrierType,
            String barrierName,
            String barrierId,
            List<List<Double>> barrierPolygon,
            String dateType,
            List<List<String>> timeRange,
            Double speedLimit) {
    }

    /**
     * 平台统一响应包装：
     * <pre>{ "code": "0000", "message": "成功", "data": {...} }</pre>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Envelope<T>(String code, String message, @JsonProperty("data") T data) {

        /** 是否成功（文档 7.1：0000 为成功）。 */
        public boolean isSuccess() {
            return "0000".equals(code);
        }
    }
}
