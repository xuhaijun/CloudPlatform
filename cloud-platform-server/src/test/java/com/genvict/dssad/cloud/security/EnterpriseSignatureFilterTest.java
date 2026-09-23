package com.genvict.dssad.cloud.security;

import com.genvict.dssad.cloud.common.exception.GlobalExceptionHandler;
import com.genvict.dssad.cloud.common.store.InMemoryStateStore;
import com.genvict.dssad.cloud.common.store.StateStore;
import com.genvict.dssad.cloud.config.AppProperties;
import com.genvict.dssad.cloud.integration.RegulatorySigner;
import com.genvict.dssad.cloud.service.MediaService;
import com.genvict.dssad.cloud.service.RemoteCockpitService;
import com.genvict.dssad.cloud.support.TestProperties;
import com.genvict.dssad.cloud.web.EnterpriseApiController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 对外接口签名鉴权过滤器（{@link EnterpriseSignatureFilter}）契约测试。
 *
 * <p>接口文档 6.3.1 把认证方式留给各企业自定，意味着<b>没有任何外部规范兜底</b>——
 * 一旦这里的防重放逻辑被改坏（比如去掉 nonce 去重、放宽时间窗），
 * 后果是这个开放接口可以被任意重放调用，而且不会有任何编译期报错。
 * 因此把每条安全约束都写成可执行的断言。
 *
 * <p>测试用手写的 SHA-256 / HMAC 独立计算签名，不复用 {@link RegulatorySigner} 的实现，
 * 否则「实现和测试一起错」时无法发现。
 */
class EnterpriseSignatureFilterTest {

    private static final String VIN = "LGAX2A130NH000001";
    private static final String ENTERPRISE_ID = TestProperties.ENTERPRISE_ID;
    private static final String BODY =
            "{\"vin\":\"" + VIN + "\",\"cameraDirection\":[\"1\"],\"type\":\"1\"}";

    private MediaService mediaService;
    private MockMvc mockMvc;
    private StateStore stateStore;
    private String secret;

    @BeforeEach
    void setUp() {
        AppProperties properties = TestProperties.defaults();
        RegulatorySigner signer = new RegulatorySigner(properties);
        stateStore = new InMemoryStateStore();

        mediaService = mock(MediaService.class);
        given(mediaService.liveVideo(any(), anyList(), any())).willReturn(java.util.List.of());

        mockMvc = MockMvcBuilders
                .standaloneSetup(new EnterpriseApiController(mediaService, mock(RemoteCockpitService.class)),
                        new MultipartProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new EnterpriseSignatureFilter(signer, stateStore))
                .build();

        // 密钥派生规则来自文档 5.2.2.1.1：secret = SHA256(clientId + "CMAT")
        secret = sha256Hex(ENTERPRISE_ID + "CMAT");
    }

    // ==================== 拒绝路径 ====================

    @Test
    @DisplayName("缺少签名请求头：2001 未授权，且不进入业务逻辑")
    void shouldRejectWhenHeadersMissing() throws Exception {
        perform(BODY, null, null, null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("2001"))
                .andExpect(jsonPath("$.message").value("缺少签名相关请求头"));

        verifyNoInteractions(mediaService);
    }

    @Test
    @DisplayName("时间戳超出 5 分钟窗口：1002 拒绝（限制抓包重放的有效期）")
    void shouldRejectExpiredTimestamp() throws Exception {
        String stale = String.valueOf(System.currentTimeMillis() - 6 * 60 * 1000L);
        String nonce = UUID.randomUUID().toString();

        perform(BODY, stale, nonce, sign(stale, nonce, BODY), ENTERPRISE_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1002"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("超出允许范围")));

        verifyNoInteractions(mediaService);
    }

    @Test
    @DisplayName("时间戳不是数字：1002 而不是 9999")
    void shouldRejectNonNumericTimestamp() throws Exception {
        perform(BODY, "not-a-number", "n-1", "deadbeef", ENTERPRISE_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1002"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("毫秒时间戳")));
    }

    @Test
    @DisplayName("签名不匹配：1002 拒绝")
    void shouldRejectWrongSignature() throws Exception {
        String ts = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();

        perform(BODY, ts, nonce, "0".repeat(64), ENTERPRISE_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1002"))
                .andExpect(jsonPath("$.message").value("签名不匹配"));

        verifyNoInteractions(mediaService);
    }

    @Test
    @DisplayName("签名正确但请求体被篡改：1002 拒绝（签名覆盖 body）")
    void shouldRejectTamperedBody() throws Exception {
        String ts = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        // 用原始 body 计算签名，却发送篡改后的 body
        String signature = sign(ts, nonce, BODY);
        String tampered = BODY.replace("\"1\"", "\"3\"");

        perform(tampered, ts, nonce, signature, ENTERPRISE_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1002"))
                .andExpect(jsonPath("$.message").value("签名不匹配"));
    }

    @Test
    @DisplayName("同一 nonce 二次使用：1002 拒绝重放")
    void shouldRejectReplayedNonce() throws Exception {
        String ts = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String signature = sign(ts, nonce, BODY);

        perform(BODY, ts, nonce, signature, ENTERPRISE_ID)
                .andExpect(jsonPath("$.code").value("0000"));

        // 完全相同的请求再来一次：签名依然有效，但 nonce 已被消费
        perform(BODY, ts, nonce, signature, ENTERPRISE_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1002"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("重放")));
    }

    @Test
    @DisplayName("企业 ID 未知：按同样规则派生密钥必然不同，签名校验失败")
    void shouldRejectUnknownEnterprise() throws Exception {
        String ts = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        // 用本平台企业 ID 派生出的密钥签名，但头里报另一个企业 ID
        String signature = sign(ts, nonce, BODY);

        perform(BODY, ts, nonce, signature, "DSSAD-OTHER-9999")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1002"))
                .andExpect(jsonPath("$.message").value("签名不匹配"));
    }

    // ==================== 放行路径 ====================

    @Test
    @DisplayName("签名合法：放行到控制器并返回 0000")
    void shouldPassValidRequest() throws Exception {
        String ts = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();

        perform(BODY, ts, nonce, sign(ts, nonce, BODY), ENTERPRISE_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0000"))
                .andExpect(jsonPath("$.data.vin").value(VIN));
    }

    @Test
    @DisplayName("multipart 请求跳过签名校验（车端媒体上传用业务态鉴权）")
    void shouldBypassSignatureForMultipart() throws Exception {
        // 该路径不在 /enterprise/api/v1 下，但同样是外网暴露的写入接口；
        // 白名单判定只看「路径前缀 + Content-Type」，与具体控制器无关，
        // 因此用一个探针控制器即可精确验证，无需启动整条媒体链路。
        mockMvc.perform(multipart("/enterprise/api/v1/probe-multipart"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bypassed").value(true));
    }

    @Test
    @DisplayName("非对外接口路径不经过签名校验")
    void shouldBypassOtherPaths() throws Exception {
        mockMvc.perform(post("/probe/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bypassed").value(true));
    }

    // ==================== 工具 ====================

    private org.springframework.test.web.servlet.ResultActions perform(
            String body, String timestamp, String nonce, String signature, String enterpriseId) throws Exception {
        var builder = post(EnterpriseSignatureFilter.PATH_PREFIX + "/video/live")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (enterpriseId != null) {
            builder = builder.header("X-Enterprise-Id", enterpriseId);
        }
        if (timestamp != null) {
            builder = builder.header("X-Timestamp", timestamp);
        }
        if (nonce != null) {
            builder = builder.header("X-Nonce", nonce);
        }
        if (signature != null) {
            builder = builder.header("X-Sign", signature);
        }
        return mockMvc.perform(builder);
    }

    private String sign(String timestamp, String nonce, String body) {
        return hmacSha256Hex(timestamp + "." + nonce + "." + body, secret);
    }

    /** 独立实现的 SHA-256（十六进制小写），不复用被测代码。 */
    private static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 独立实现的 HMAC-SHA256（十六进制小写）。 */
    private static String hmacSha256Hex(String payload, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 探针控制器：唯一职责是证明「某条请求穿过了过滤器」。
     *
     * <p>比启动真实的 {@code AccidentMediaUploadController} 更轻，也更聚焦——
     * 白名单判定发生在过滤器内部，与控制器实现无关。
     */
    @RestController
    static class MultipartProbeController {

        @PostMapping("/enterprise/api/v1/probe-multipart")
        public java.util.Map<String, Object> probe() {
            return java.util.Map.of("bypassed", true);
        }

        @PostMapping("/probe/public")
        public java.util.Map<String, Object> publicProbe() {
            return java.util.Map.of("bypassed", true);
        }
    }
}
