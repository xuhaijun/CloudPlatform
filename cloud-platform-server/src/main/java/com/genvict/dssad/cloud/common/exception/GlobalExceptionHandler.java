package com.genvict.dssad.cloud.common.exception;

import com.genvict.dssad.cloud.common.api.ApiResponse;
import com.genvict.dssad.cloud.common.api.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器。
 *
 * <p>设计要点：
 * <ul>
 *   <li>对外（含监管平台）HTTP 状态码统一返回 200，错误信息通过 body 中的 {@code code} 表达，
 *       与接口文档「统一返回数据结构」保持一致，避免对端把 4xx/5xx 当作网络故障重试。</li>
 *   <li>仅对系统级异常打印 error 堆栈并登记告警，业务异常只打印 warn，避免日志噪音。</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：可直接预期，回传错误码。 */
    @ExceptionHandler(BizException.class)
    public ApiResponse<Void> handleBiz(BizException ex, HttpServletRequest request) {
        log.warn("[业务异常] uri={} code={} msg={}", request.getRequestURI(), ex.getCode(), ex.getMessage());
        return ApiResponse.fail(ex.getCode(), ex.getMessage());
    }

    /** @Valid 校验失败（请求体）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> handleValid(MethodArgumentNotValidException ex) {
        return ApiResponse.fail(ErrorCode.PARAM_INVALID, collectFieldErrors(ex.getBindingResult().getFieldErrors()));
    }

    /** @Valid 校验失败（表单/查询参数绑定）。 */
    @ExceptionHandler(BindException.class)
    public ApiResponse<Void> handleBind(BindException ex) {
        return ApiResponse.fail(ErrorCode.PARAM_INVALID, collectFieldErrors(ex.getBindingResult().getFieldErrors()));
    }

    /** 必填请求参数缺失。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ApiResponse<Void> handleMissingParam(MissingServletRequestParameterException ex) {
        return ApiResponse.fail(ErrorCode.PARAM_INVALID, "缺少必填参数：" + ex.getParameterName());
    }

    /** 参数类型不匹配。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ApiResponse<Void> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ApiResponse.fail(ErrorCode.PARAM_INVALID, "参数类型不正确：" + ex.getName());
    }

    /** 请求体不是合法 JSON。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ApiResponse<Void> handleUnreadable(HttpMessageNotReadableException ex) {
        return ApiResponse.fail(ErrorCode.PARAM_INVALID, "请求体不是合法的JSON");
    }

    /** 方法不支持。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ApiResponse<Void> handleMethod(HttpRequestMethodNotSupportedException ex) {
        return ApiResponse.fail(ErrorCode.PARAM_INVALID, "不支持的请求方法：" + ex.getMethod());
    }

    /** 404。 */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ApiResponse<Void> handleNotFound(NoHandlerFoundException ex) {
        return ApiResponse.fail(ErrorCode.NOT_FOUND, "接口不存在：" + ex.getRequestURL());
    }

    /** 兜底：系统内部错误。 */
    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("[系统异常] uri={}", request.getRequestURI(), ex);
        return ApiResponse.fail(ErrorCode.SYSTEM_ERROR);
    }

    private String collectFieldErrors(java.util.List<FieldError> errors) {
        if (errors == null || errors.isEmpty()) {
            return ErrorCode.PARAM_INVALID.getMessage();
        }
        return errors.stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
    }
}
