package com.genvict.dssad.cloud.security;

import com.genvict.dssad.cloud.common.api.ApiResponse;
import com.genvict.dssad.cloud.common.api.ErrorCode;
import com.genvict.dssad.cloud.common.store.StateStore;
import com.genvict.dssad.cloud.common.util.JsonUtils;
import com.genvict.dssad.cloud.common.util.TimeUtils;
import com.genvict.dssad.cloud.integration.RegulatorySigner;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * 企业云端对外接口（{@code /enterprise/api/v1/**}）的签名鉴权过滤器。
 *
 * <p>接口文档 6.3.1 明确「各企业根据自己已有平台的接口认证方式实现」，
 * 故本平台自定义一套<b>防重放</b>的签名方案（比裸 Token 更安全，也便于与监管平台联调对账）：
 *
 * <pre>
 * 请求头：
 *   X-Enterprise-Id : 企业 ID（准入平台分配）
 *   X-Timestamp     : 13 位毫秒时间戳（东八区 UTC 时间）
 *   X-Nonce         : 一次性随机串（UUID）
 *   X-Sign          : HMAC-SHA256(secret, timestamp + "." + nonce + "." + body) 的小写十六进制
 * 其中 secret = SHA256(enterpriseId + secretSalt)，与文档 5.2.2.1.1 的密钥派生方式保持一致
 * </pre>
 *
 * <p>三重防护：
 * <ol>
 *   <li><b>时间窗</b>：{@code X-Timestamp} 与服务端时间偏差超过 5 分钟直接拒绝 ——
 *       限制被抓包后重放的有效期；</li>
 *   <li><b>随机数</b>：{@code X-Nonce} 在 10 分钟窗口内只允许使用一次（状态存储去重）—— 彻底阻断重放；</li>
 *   <li><b>常量时间比较</b>：签名比对使用 {@link java.security.MessageDigest#isEqual}，抵御时序攻击。</li>
 * </ol>
 *
 * <p>实现为 Filter 而非 Interceptor：需要在读取请求体<b>之前</b>完成包装，
 * 否则下游 Controller 会读到已被消费的空 body。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class EnterpriseSignatureFilter extends OncePerRequestFilter {

    /** 对外接口路径前缀（文档 6.3.2）。 */
    public static final String PATH_PREFIX = "/enterprise/api/v1";

    /** 时间戳允许偏差。 */
    private static final long TIMESTAMP_TOLERANCE_MILLIS = Duration.ofMinutes(5).toMillis();

    /** Nonce 去重窗口。 */
    private static final Duration NONCE_TTL = Duration.ofMinutes(10);

    private final RegulatorySigner signer;
    private final StateStore stateStore;

    public EnterpriseSignatureFilter(RegulatorySigner signer, StateStore stateStore) {
        this.signer = signer;
        this.stateStore = stateStore;
    }

    /**
     * 白名单：媒体上传（multipart）不做签名校验。
     *
     * <p>原因：multipart 的 body 是二进制文件（可达 200MB），把它读进内存做 HMAC 会带来
     * 严重的内存与延迟问题；该接口改用 MQTT 阶段下发的上传指令做鉴权
     * （车端只能对平台已经请求过的事件上传视频，见 {@code AccidentMediaServiceImpl}）。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!request.getRequestURI().startsWith(PATH_PREFIX)) {
            return true;
        }
        String contentType = request.getContentType();
        return contentType != null && contentType.startsWith(MediaType.MULTIPART_FORM_DATA_VALUE);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        CachedBodyRequestWrapper wrapped = new CachedBodyRequestWrapper(request);
        try {
            verify(wrapped);
        } catch (SignatureException e) {
            log.warn("[鉴权] 签名校验失败 uri={} 原因={}", request.getRequestURI(), e.getMessage());
            writeError(response, e.errorCode(), e.getMessage());
            return;
        }
        filterChain.doFilter(wrapped, response);
    }

    private void verify(CachedBodyRequestWrapper request) throws SignatureException {
        String enterpriseId = request.getHeader("X-Enterprise-Id");
        String timestamp = request.getHeader("X-Timestamp");
        String nonce = request.getHeader("X-Nonce");
        String signature = request.getHeader("X-Sign");

        if (!StringUtils.hasText(enterpriseId) || !StringUtils.hasText(timestamp)
                || !StringUtils.hasText(nonce) || !StringUtils.hasText(signature)) {
            throw new SignatureException(ErrorCode.UNAUTHORIZED, "缺少签名相关请求头");
        }

        long requestTime;
        try {
            requestTime = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            throw new SignatureException(ErrorCode.SIGN_INVALID, "X-Timestamp 不是合法的毫秒时间戳");
        }
        long skew = Math.abs(TimeUtils.nowMillis() - requestTime);
        if (skew > TIMESTAMP_TOLERANCE_MILLIS) {
            throw new SignatureException(ErrorCode.SIGN_INVALID,
                    "请求时间偏差 " + skew + "ms 超出允许范围（5 分钟）");
        }

        // Nonce 一次性：同一 nonce 第二次出现即视为重放
        String nonceKey = "auth:nonce:" + enterpriseId + ':' + nonce;
        if (!stateStore.setIfAbsent(nonceKey, "1", NONCE_TTL)) {
            throw new SignatureException(ErrorCode.SIGN_INVALID, "重复的 X-Nonce，疑似重放攻击");
        }

        String secret = signer.secret(enterpriseId);
        String expected = signer.hmacSha256(timestamp + "." + nonce + "." + request.getBodyAsString(), secret);
        if (!signer.verify(expected, signature)) {
            throw new SignatureException(ErrorCode.SIGN_INVALID, "签名不匹配");
        }
    }

    private void writeError(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(JsonUtils.toJson(ApiResponse.fail(code, message)));
        response.getWriter().flush();
    }

    /** 签名校验失败。 */
    private static final class SignatureException extends RuntimeException {

        private final ErrorCode errorCode;

        SignatureException(ErrorCode errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        ErrorCode errorCode() {
            return errorCode;
        }
    }
}
