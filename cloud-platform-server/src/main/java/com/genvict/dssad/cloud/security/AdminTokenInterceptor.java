package com.genvict.dssad.cloud.security;

import com.genvict.dssad.cloud.common.api.ApiResponse;
import com.genvict.dssad.cloud.common.api.ErrorCode;
import com.genvict.dssad.cloud.common.util.JsonUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理端接口令牌校验拦截器（{@code /api/v1/**}）。
 *
 * <p>白名单（无需令牌）：
 * <ul>
 *   <li>{@code /api/v1/auth/login}：登录接口本身；</li>
 *   <li>OPTIONS 预检：CORS 预检请求不携带自定义头，必须放行，否则跨域直接失败。</li>
 * </ul>
 *
 * <p><b>关于 {@code /actuator/**}</b>：本拦截器由 {@code WebConfig} 注册，路径模式只覆盖
 * {@code /api/v1/**}，因此 <u>Actuator 端点根本不会进入本拦截器</u>——
 * 下方 {@code uri.startsWith("/actuator")} 是保持防御性而<b>不再承担安全职责</b>的死分支。
 * Actuator 的访问控制由 {@link ActuatorIpWhitelistFilter}（来源 IP 白名单，fail-closed）负责，
 * 不要因为看到这一行就认为 Actuator 已被保护。
 */
@Component
@RequiredArgsConstructor
public class AdminTokenInterceptor implements HandlerInterceptor {

    /** 令牌请求头名称。 */
    public static final String TOKEN_HEADER = "X-Token";

    private static final String LOGIN_PATH = "/api/v1/auth/login";

    private final TokenService tokenService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String uri = request.getRequestURI();
        if (uri.startsWith(LOGIN_PATH) || uri.startsWith("/actuator")) {
            return true;
        }
        String token = request.getHeader(TOKEN_HEADER);
        if (tokenService.validate(token).isPresent()) {
            return true;
        }
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(JsonUtils.toJson(
                ApiResponse.fail(ErrorCode.UNAUTHORIZED, "未授权或Token已失效，请重新登录")));
        response.getWriter().flush();
        return false;
    }
}
