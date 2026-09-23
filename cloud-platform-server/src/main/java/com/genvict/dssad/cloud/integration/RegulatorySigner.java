package com.genvict.dssad.cloud.integration;

import com.genvict.dssad.cloud.config.AppProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 对接安全工具（密钥派生 + HMAC 签名）。
 *
 * <p>两类用途：
 * <ol>
 *   <li><b>向监管平台证明身份</b>：文档 5.2.2.1.1 / 6.2.2.1.1 规定
 *       {@code secret = SHA256(clientId + "CMAT")}，用该密钥换取 MQTT 连接密码；</li>
 *   <li><b>校验监管平台/企业内部调用方</b>：平台对外开放的企业云端接口（6.3）文档
 *       「各企业根据自己已有平台的接口认证方式实现」，本平台采用
 *       {@code HMAC-SHA256(timestamp + "." + nonce + "." + body)} 方案，
 *       相比裸 Token 能防重放（timestamp + nonce 双因子）且无需传输密钥本身。</li>
 * </ol>
 *
 * <p>安全注意：本类只做摘要与签名，不落任何密钥到日志；{@link #secret} 的返回值
 * 仅用于请求体，禁止打印。
 */
@Component
public class RegulatorySigner {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final String salt;

    public RegulatorySigner(AppProperties properties) {
        this.salt = properties.regulatory().secretSalt();
    }

    /**
     * 派生密钥：{@code SHA256(clientId + salt)}，返回小写十六进制字符串。
     *
     * @param clientId 企业 ID（或车端 VIN）
     */
    public String secret(String clientId) {
        if (clientId == null) {
            throw new IllegalArgumentException("clientId 不能为空");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((clientId + salt).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，正常不会发生
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }

    /**
     * 计算 HMAC-SHA256 签名（小写十六进制）。
     *
     * @param payload  待签名内容，约定为 {@code timestamp + "." + nonce + "." + body}
     * @param secret   共享密钥
     */
    public String hmacSha256(String payload, String secret) {
        if (payload == null || secret == null) {
            throw new IllegalArgumentException("签名内容与密钥均不能为空");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] result = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(result);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 计算失败", e);
        }
    }

    /**
     * 常量时间比较签名，避免时序攻击。
     *
     * <p>普通 {@code equals} 一旦发现第一个不同字符就返回，攻击者可通过响应时间差逐字节爆破签名；
     * {@link MessageDigest#isEqual} 内部按位累积差异，耗时与内容无关。
     */
    public boolean verify(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.toLowerCase().getBytes(StandardCharsets.UTF_8),
                actual.toLowerCase().getBytes(StandardCharsets.UTF_8));
    }
}
