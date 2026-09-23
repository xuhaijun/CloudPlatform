package com.genvict.dssad.cloud.security;

import com.genvict.dssad.cloud.common.store.InMemoryStateStore;
import com.genvict.dssad.cloud.common.store.StateStore;
import com.genvict.dssad.cloud.config.AppProperties;
import com.genvict.dssad.cloud.integration.RegulatorySigner;
import com.genvict.dssad.cloud.ratelimit.RateLimiter;
import com.genvict.dssad.cloud.support.TestProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全与限流测试：密钥派生、HMAC 签名、固定窗口限流。
 *
 * <p>期望值用 JDK 原生 {@link MessageDigest} 在测试内<b>独立计算</b>，
 * 而不是复制实现里的常量 —— 否则「实现算错、测试也照抄」就失去了校验意义。
 */
@DisplayName("安全与限流")
class SecurityAndRateLimitTest {

    // ==================== 签名 ====================

    @Nested
    @DisplayName("RegulatorySigner：密钥派生与 HMAC")
    class Signer {

        private final RegulatorySigner signer = new RegulatorySigner(TestProperties.defaults());

        @Test
        @DisplayName("密钥派生遵循文档 5.2.2.1.1：SHA256(clientId + \"CMAT\") 的小写十六进制")
        void secretMatchesDocumentFormula() throws Exception {
            String clientId = "DSSAD-TEST-0001";
            String expected = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest((clientId + "CMAT").getBytes(StandardCharsets.UTF_8)));

            assertEquals(expected, signer.secret(clientId));
            assertEquals(64, signer.secret(clientId).length(), "SHA-256 十六进制应为 64 个字符");
        }

        @Test
        @DisplayName("不同 clientId 派生不同密钥（否则一车泄露即全网沦陷）")
        void differentClientIdsYieldDifferentSecrets() {
            assertNotEquals(signer.secret("VIN-A"), signer.secret("VIN-B"));
        }

        @Test
        @DisplayName("HMAC-SHA256 对同一输入稳定，对内容变化敏感")
        void hmacIsDeterministicAndSensitive() {
            String secret = signer.secret("DSSAD-TEST-0001");

            String first = signer.hmacSha256("1700000000000.nonce-1.{\"a\":1}", secret);
            String again = signer.hmacSha256("1700000000000.nonce-1.{\"a\":1}", secret);
            assertEquals(first, again, "同样输入必须得到同样签名，否则对端无法校验");

            String tamperedBody = signer.hmacSha256("1700000000000.nonce-1.{\"a\":2}", secret);
            assertNotEquals(first, tamperedBody, "报文体被篡改后签名必须变化");

            String tamperedNonce = signer.hmacSha256("1700000000000.nonce-2.{\"a\":1}", secret);
            assertNotEquals(first, tamperedNonce, "防重放的核心：nonce 变化签名必须变化");
        }

        @Test
        @DisplayName("verify 使用常量时间比较，且对长度不同的签名安全返回 false")
        void verifyIsSafeForWrongInput() {
            String secret = signer.secret("ENT-1");
            String expected = signer.hmacSha256("data", secret);

            assertTrue(signer.verify(expected, expected));
            assertTrue(signer.verify(expected, expected.toUpperCase()),
                    "十六进制大小写不应影响判定（对接方实现不统一是常态）");

            assertFalse(signer.verify(expected, "deadbeef"), "长度不同的签名不能抛异常，应返回 false");
            assertFalse(signer.verify(expected, ""));
            assertFalse(signer.verify(expected, null));
            assertFalse(signer.verify(null, expected));
        }
    }

    // ==================== 限流 ====================

    @Nested
    @DisplayName("RateLimiter：固定窗口限流（文档 9.1）")
    class Limiter {

        @Test
        @DisplayName("窗口内允许到上限，第 limit+1 次被拒，不同主体互不影响")
        void allowsUpToLimitPerSubject() {
            StateStore store = new InMemoryStateStore();
            RateLimiter limiter = new RateLimiter(store, TestProperties.defaults());
            Duration window = Duration.ofMinutes(1);

            for (int i = 1; i <= 5; i++) {
                assertTrue(limiter.tryAcquire("test", "VIN-A", 5, window),
                        "第 " + i + " 次（≤ 上限）应放行");
            }
            assertFalse(limiter.tryAcquire("test", "VIN-A", 5, window), "超过上限必须被拒");
            assertFalse(limiter.tryAcquire("test", "VIN-A", 5, window), "持续超限应持续被拒");

            // 限流主体隔离：A 被限流不影响 B（否则一辆车的高频请求会拖垮全网）
            assertTrue(limiter.tryAcquire("test", "VIN-B", 5, window), "限流必须按主体隔离");
        }

        @Test
        @DisplayName("进入新的时间窗口后计数重置")
        void resetsInNextWindow() {
            StateStore store = new InMemoryStateStore();
            RateLimiter limiter = new RateLimiter(store, TestProperties.defaults());
            // 用 1 秒窗口验证跨窗口行为，避免测试等待 1 分钟
            Duration window = Duration.ofSeconds(1);

            assertTrue(limiter.tryAcquire("win", "VIN-A", 1, window));
            assertFalse(limiter.tryAcquire("win", "VIN-A", 1, window));

            // 等到下一个窗口（固定窗口按 now/窗口长度 取整分桶）
            long deadline = System.currentTimeMillis() + 2_000;
            boolean allowed = false;
            while (System.currentTimeMillis() < deadline) {
                if (limiter.tryAcquire("win", "VIN-A", 1, window)) {
                    allowed = true;
                    break;
                }
                sleep(50);
            }
            assertTrue(allowed, "跨入新窗口后必须恢复放行，否则限流会永久封死车辆");
        }

        @Test
        @DisplayName("limit<=0 或 id 为空表示不限流，配置关闭时全部放行")
        void disabledOrZeroLimitAlwaysAllows() {
            StateStore store = new InMemoryStateStore();
            RateLimiter enabled = new RateLimiter(store, TestProperties.defaults());
            for (int i = 0; i < 100; i++) {
                assertTrue(enabled.tryAcquire("t", "VIN-A", 0, Duration.ofMinutes(1)));
            }
            assertTrue(enabled.tryAcquire("t", null, 1, Duration.ofMinutes(1)));

            RateLimiter disabled = new RateLimiter(store, TestProperties.rateLimitDisabled());
            for (int i = 0; i < 1_000; i++) {
                assertTrue(disabled.tryAcquire("t", "VIN-A", 1, Duration.ofMinutes(1)),
                        "配置关闭后不得限流");
            }
        }

        @Test
        @DisplayName("契约值来自配置：单车辆 HTTP 100 次/分钟、MQTT 发布 10 条/秒")
        void documentLimitsComeFromConfig() {
            RateLimiter limiter = new RateLimiter(new InMemoryStateStore(), TestProperties.defaults());
            assertEquals(100, limiter.config().httpPerMinute());
            assertEquals(10, limiter.config().mqttPerSecond());
        }

        @Test
        @DisplayName("状态存储降级（返回 0）时放行而不是误伤业务")
        void degradesOpenWhenStoreUnavailable() {
            // 模拟 Redis 抖动：increment 永远返回 0
            StateStore broken = new StateStore() {
                @Override
                public void put(String key, String value, Duration ttl) {
                }

                @Override
                public java.util.Optional<String> get(String key) {
                    return java.util.Optional.empty();
                }

                @Override
                public boolean setIfAbsent(String key, String value, Duration ttl) {
                    return true;
                }

                @Override
                public long increment(String key, Duration ttl) {
                    return 0L;
                }

                @Override
                public void delete(String key) {
                }
            };
            RateLimiter limiter = new RateLimiter(broken, TestProperties.defaults());

            for (int i = 0; i < 500; i++) {
                assertTrue(limiter.tryAcquire("t", "VIN-A", 1, Duration.ofMinutes(1)),
                        "计数不可用时必须放行：限流失效只是短暂超限，误伤会让正常报文全部失败");
            }
        }

        private void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ==================== 令牌 ====================

    @Nested
    @DisplayName("TokenService：平台访问令牌")
    class Tokens {

        private final InMemoryStateStore store = new InMemoryStateStore();
        private final TokenService tokenService = new TokenService(store);

        @Test
        @DisplayName("签发后可校验，注销后立即失效")
        void issueValidateRevoke() {
            String token = tokenService.issue("admin");
            assertEquals("admin", tokenService.validate(token).orElseThrow());

            tokenService.revoke(token);
            assertTrue(tokenService.validate(token).isEmpty(), "注销后必须立即失效");
        }

        @Test
        @DisplayName("令牌不可猜测（每次签发都不同）且非法入参安全返回空")
        void tokensAreUnpredictable() {
            assertNotEquals(tokenService.issue("admin"), tokenService.issue("admin"));
            assertTrue(tokenService.validate(null).isEmpty());
            assertTrue(tokenService.validate("").isEmpty());
            assertTrue(tokenService.validate("forged-token").isEmpty());
        }
    }
}
