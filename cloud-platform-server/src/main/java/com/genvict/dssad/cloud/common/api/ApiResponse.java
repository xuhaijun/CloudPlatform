package com.genvict.dssad.cloud.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 平台统一响应体。
 *
 * <p>结构与《平台数据对接接口文档 V1.0.0》第 7.1 节「平台云端 HTTP 接口错误码定义」保持一致：
 * <pre>
 * { "code": "0000", "message": "成功", "data": {...} }
 * </pre>
 * 文档约定：成功错误码为 {@code 0000}；无数据时 {@code data} 为空。
 *
 * @param <T> 业务数据类型
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiResponse<T>(String code, String message, T data) {

    /** 成功并携带数据。 */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    /** 成功但无数据。 */
    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), null);
    }

    /** 失败（错误码内置消息）。 */
    public static <T> ApiResponse<T> fail(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /** 失败（标准错误码 + 自定义可读消息）。 */
    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.getCode(), message, null);
    }

    /** 失败（自定义码 + 自定义消息）。 */
    public static <T> ApiResponse<T> fail(String code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    /** 是否为成功响应，供对端（监管平台/前端）快速判定。 */
    public boolean isSuccess() {
        return ErrorCode.SUCCESS.getCode().equals(code);
    }
}
