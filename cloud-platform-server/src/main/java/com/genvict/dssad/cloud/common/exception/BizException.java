package com.genvict.dssad.cloud.common.exception;

import com.genvict.dssad.cloud.common.api.ErrorCode;
import lombok.Getter;

/**
 * 业务异常。
 *
 * <p>所有可预期的业务失败均抛出本异常，由 {@link GlobalExceptionHandler} 统一转换为
 * {@link com.genvict.dssad.cloud.common.api.ApiResponse}，避免在 Controller 中散落 if-else。
 */
@Getter
public class BizException extends RuntimeException {

    private final String code;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    public BizException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** 快捷抛出：资源不存在。 */
    public static BizException notFound(String message) {
        return new BizException(ErrorCode.NOT_FOUND, message);
    }

    /** 快捷抛出：参数不合法。 */
    public static BizException paramInvalid(String message) {
        return new BizException(ErrorCode.PARAM_INVALID, message);
    }
}
