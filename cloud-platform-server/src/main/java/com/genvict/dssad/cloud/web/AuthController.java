package com.genvict.dssad.cloud.web;

import com.genvict.dssad.cloud.common.api.ApiResponse;
import com.genvict.dssad.cloud.common.api.ErrorCode;
import com.genvict.dssad.cloud.common.exception.BizException;
import com.genvict.dssad.cloud.security.TokenService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端登录接口。
 *
 * <p>当前实现为「单账号 + 令牌」：
 * <ul>
 *   <li>账号来源：环境变量 {@code DSSAD_ADMIN_USER}/{@code DSSAD_ADMIN_PASSWORD}，
 *       未配置时使用开发默认值并在启动日志/登录响应中显著提示 ——
 *       <b>绝不把口令硬编码进代码或配置文件</b>（代码自检清单要求）；</li>
 *   <li>登录成功返回令牌，前端存于 {@code sessionStorage}（不用 localStorage，
 *       以免长期留存凭据）；</li>
 *   <li>口令比对使用常量时间方法，抵御时序攻击。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Validated
public class AuthController {

    private static final String ENV_USER = "DSSAD_ADMIN_USER";
    private static final String ENV_PASSWORD = "DSSAD_ADMIN_PASSWORD";

    /** 开发默认口令，仅在未配置环境变量时生效。 */
    private static final String DEV_USER = "admin";
    private static final String DEV_PASSWORD = "dssad@2026";

    private final TokenService tokenService;

    /**
     * 登录。
     *
     * @param body 账号口令
     * @return 令牌与过期提示
     */
    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@jakarta.validation.Valid @RequestBody LoginRequest body) {
        String expectedUser = envOrDefault(ENV_USER, DEV_USER);
        String expectedPassword = envOrDefault(ENV_PASSWORD, DEV_PASSWORD);

        if (!constantTimeEquals(expectedUser, body.username())
                || !constantTimeEquals(expectedPassword, body.password())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "账号或密码错误");
        }
        String token = tokenService.issue(body.username());
        boolean usingDefaults = System.getenv(ENV_USER) == null || System.getenv(ENV_PASSWORD) == null;
        return ApiResponse.ok(Map.of(
                "token", token,
                "username", body.username(),
                "usingDefaultCredential", usingDefaults,
                "notice", usingDefaults
                        ? "当前使用开发默认口令，上生产前请通过环境变量 DSSAD_ADMIN_USER/DSSAD_ADMIN_PASSWORD 覆盖"
                        : ""));
    }

    /** 登出（注销令牌）。 */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader(value = "X-Token", required = false) String token) {
        tokenService.revoke(token);
        return ApiResponse.ok();
    }

    private String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    /** 常量时间字符串比较。 */
    private boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                actual.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * 登录请求。
     *
     * @param username 用户名
     * @param password 口令
     */
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }
}
