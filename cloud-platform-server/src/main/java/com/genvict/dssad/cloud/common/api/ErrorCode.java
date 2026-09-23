package com.genvict.dssad.cloud.common.api;

import lombok.Getter;

/**
 * 平台错误码字典。
 *
 * <p>区间划分（与接口文档 7.1 节兼容，成功码固定 {@code 0000}）：
 * <ul>
 *   <li>{@code 0xxx} 成功</li>
 *   <li>{@code 1xxx} 请求/参数/签名</li>
 *   <li>{@code 2xxx} 认证鉴权</li>
 *   <li>{@code 3xxx} 资源</li>
 *   <li>{@code 4xxx} 限流与配额</li>
 *   <li>{@code 5xxx} 外部依赖（MQTT / 监管平台 / 存储）</li>
 *   <li>{@code 9xxx} 系统内部</li>
 * </ul>
 */
@Getter
public enum ErrorCode {

    /** 成功。 */
    SUCCESS("0000", "成功"),

    /** 请求参数不合法（字段缺失、类型错误、越界）。 */
    PARAM_INVALID("1001", "请求参数不合法"),
    /** 签名校验失败（云云接口 HMAC 校验不通过）。 */
    SIGN_INVALID("1002", "签名校验失败"),
    /** 请求体超出文档约定的体积上限。 */
    PAYLOAD_TOO_LARGE("1003", "请求体超出限制"),

    /** 未授权或 Token 已失效。 */
    UNAUTHORIZED("2001", "未授权或Token已失效"),

    /** 资源不存在。 */
    NOT_FOUND("3001", "资源不存在"),
    /** 资源已存在（幂等冲突）。 */
    DUPLICATE("3002", "资源已存在"),

    /** 触发限流（文档 9.1：HTTP 100 次/分钟·车）。 */
    RATE_LIMITED("4001", "请求过于频繁，已触发限流"),

    /** MQTT 通道不可用（未连接 / 发布失败）。 */
    MQTT_UNAVAILABLE("5001", "MQTT通道不可用"),
    /** 监管平台接口调用失败。 */
    UPSTREAM_ERROR("5002", "监管平台调用失败"),
    /** 媒体（视频）存储异常。 */
    STORAGE_ERROR("5003", "媒体存储异常"),

    /** 系统内部错误。 */
    SYSTEM_ERROR("9999", "系统内部错误");

    private final String code;
    private final String message;

    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }
}
