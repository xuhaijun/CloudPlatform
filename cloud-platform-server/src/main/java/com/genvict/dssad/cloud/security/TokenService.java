package com.genvict.dssad.cloud.security;

import com.genvict.dssad.cloud.common.store.StateStore;
import com.genvict.dssad.cloud.common.util.UuidUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Optional;

/**
 * 平台访问令牌服务（管理前端 / 运营接口鉴权）。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>不引入 Spring Security</b>：本平台的鉴权需求很窄（前端单一角色、内部可信网络），
 *       引入完整安全框架会带来配置复杂度与启动开销；用「拦截器 + 令牌」20 行代码即可覆盖，
 *       待出现多角色 RBAC 需求时再平滑升级。</li>
 *   <li><b>令牌不可猜测</b>：使用 UUID v4（122 位随机），而非自增 ID 或时间戳派生；</li>
 *   <li><b>状态存储承载</b>：默认内存实现（单机），启用 Redis 后天然支持多实例共享登录态；</li>
 *   <li><b>可主动失效</b>：{@link #revoke} 支持登出与密钥轮换场景。</li>
 * </ul>
 *
 * <p>安全提醒：令牌通过 {@code X-Token} 头传输，生产环境必须启用 HTTPS（文档 4.1 要求 TLS 1.2+），
 * 否则令牌在链路上是明文。
 */
@Slf4j
@Service
public class TokenService {

    /** 令牌有效期。 */
    private static final Duration TOKEN_TTL = Duration.ofHours(12);

    private static final String KEY_PREFIX = "auth:token:";

    private final StateStore stateStore;

    public TokenService(StateStore stateStore) {
        this.stateStore = stateStore;
    }

    /**
     * 签发令牌。
     *
     * @param subject 使用者标识（用户名或调用方 ID）
     * @return 令牌字符串
     */
    public String issue(String subject) {
        String token = UuidUtils.random();
        stateStore.put(KEY_PREFIX + token, subject, TOKEN_TTL);
        log.info("[鉴权] 已签发访问令牌 subject={} 有效期={}小时", subject, TOKEN_TTL.toHours());
        return token;
    }

    /** 校验令牌，返回使用者标识。 */
    public Optional<String> validate(String token) {
        if (!StringUtils.hasText(token)) {
            return Optional.empty();
        }
        return stateStore.get(KEY_PREFIX + token);
    }

    /** 注销令牌。 */
    public void revoke(String token) {
        if (StringUtils.hasText(token)) {
            stateStore.delete(KEY_PREFIX + token);
        }
    }
}
