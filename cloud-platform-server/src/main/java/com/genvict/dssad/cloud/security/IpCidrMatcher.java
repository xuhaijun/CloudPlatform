package com.genvict.dssad.cloud.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * 单个 CIDR 网段的匹配器（IPv4 / IPv6 / IPv4-mapped IPv6）。
 *
 * <p>专为「来源 IP 白名单」这一场景而写，刻意<b>不</b>依赖 Apache Commons Net 之类的通用库：
 * 白名单是安全边界，实现越少越好审。
 *
 * <p>几条刻意的设计约束：
 * <ul>
 *   <li><b>解析失败必须抛异常，不能静默返回「不匹配」</b>。若把非法配置当成不匹配，
 *       运维配错一个字符就会得到一份「以为设了、其实全拒」的白名单；
 *       反之若当成匹配，就等于白名单失效。两种情况都比启动失败更危险，
 *       所以非法配置在<b>构建期</b>就炸掉（过滤器构造器调用本类，见 {@code ActuatorIpWhitelistFilter}）。</li>
 *   <li><b>IPv4 自己解析，绝不触碰 {@link InetAddress#getByName}</b>：
 *       该方法对「看起来像主机名」的输入（如 {@code deadbeef}）会发起 DNS 查询，
 *       把一个纯字符串比较变成一次可被劫持的网络调用。IPv4 字面量手工解析即可。</li>
 *   <li><b>IPv6 才用 {@code getByName}</b>：IPv6 字面量<b>必须</b>含 {@code ':'}，
 *       而主机名不允许含 {@code ':'}，因此不存在被当主机名解析的可能。</li>
 *   <li><b>归一化 IPv4-mapped IPv6</b>：Tomcat 在双栈 socket 上接受 IPv4 连接时，
 *       {@code getRemoteAddr()} 可能返回 {@code ::ffff:127.0.0.1}。
 *       不归一化的话，配了 {@code 127.0.0.1/32} 却拦不住/放不过本机请求。</li>
 * </ul>
 */
public final class IpCidrMatcher {

    /** IPv4 地址位数。 */
    private static final int IPV4_BITS = 32;

    /** 单 IP 段最多允许的长度校验：IPv4 每段最多 3 位。 */
    private static final int IPV4_MAX_SEGMENT_DIGITS = 3;

    /** IPv4-mapped IPv6 前缀的固定形态：前 10 字节为 0，第 11、12 字节为 0xFF。 */
    private static final int MAPPED_PREFIX_ZERO_BYTES = 10;

    /** 原始配置文本（仅用于日志与 {@link #toString()}）。 */
    private final String source;

    /** 归一化并已按掩码清零的网络地址（4 或 16 字节）。 */
    private final byte[] network;

    /** 前缀长度（位）。 */
    private final int prefixBits;

    private IpCidrMatcher(String source, byte[] network, int prefixBits) {
        this.source = source;
        this.network = network;
        this.prefixBits = prefixBits;
    }

    /**
     * 解析一条 CIDR 配置。
     *
     * <p>接受的形式：{@code 127.0.0.1}（等价 {@code /32}）、{@code 10.0.0.0/8}、
     * {@code ::1}（等价 {@code /128}）、{@code fe80::/10}。
     *
     * @param cidr 配置文本
     * @return 匹配器
     * @throws IllegalArgumentException 地址或掩码非法（**刻意 fail-fast**）
     */
    public static IpCidrMatcher of(String cidr) {
        if (cidr == null || cidr.isBlank()) {
            throw new IllegalArgumentException("CIDR 配置不能为空");
        }
        String text = cidr.trim();
        String addressPart = text;
        int prefixBits = -1;

        int slash = text.indexOf('/');
        if (slash >= 0) {
            addressPart = text.substring(0, slash).trim();
            String bitsText = text.substring(slash + 1).trim();
            try {
                prefixBits = Integer.parseInt(bitsText);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("非法 CIDR（掩码不是整数）：" + text);
            }
        }

        byte[] address = parseAddress(addressPart, text);
        int maxBits = address.length * 8;
        if (prefixBits < 0) {
            prefixBits = maxBits;
        }
        if (prefixBits > maxBits) {
            throw new IllegalArgumentException("非法 CIDR（掩码 " + prefixBits + " 超出 " + maxBits + "）：" + text);
        }
        return new IpCidrMatcher(text, maskOut(address, prefixBits), prefixBits);
    }

    /**
     * 批量解析，任一条非法即整体失败。
     *
     * @param cidrs 配置文本数组，允许 {@code null}
     * @return 匹配器列表（跳过空白项；**空列表表示拒绝所有来源**，由调用方决定语义）
     * @throws IllegalArgumentException 存在非法配置
     */
    public static List<IpCidrMatcher> parseAll(String[] cidrs) {
        List<IpCidrMatcher> matchers = new ArrayList<>();
        if (cidrs == null) {
            return matchers;
        }
        for (String cidr : cidrs) {
            if (cidr == null || cidr.isBlank()) {
                continue;
            }
            matchers.add(of(cidr));
        }
        return matchers;
    }

    /**
     * 判断来源地址是否落在本网段内。
     *
     * <p>输入不可解析（例如容器环境下拿到了带端口 / 带 zone 的畸形字符串）时返回 {@code false}——
     * 白名单的失败方向必须是「拒绝」。
     *
     * @param ip 来源 IP（裸地址，不带端口）
     * @return 是否命中
     */
    public boolean matches(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        byte[] candidate;
        try {
            candidate = parseAddress(ip.trim(), ip);
        } catch (IllegalArgumentException e) {
            return false;
        }
        // 地址族不同直接不匹配（IPv4 规则不套用到 IPv6，反之亦然）
        if (candidate.length != network.length) {
            return false;
        }
        int fullBytes = prefixBits / 8;
        int remainingBits = prefixBits % 8;
        for (int i = 0; i < fullBytes; i++) {
            if (network[i] != candidate[i]) {
                return false;
            }
        }
        if (remainingBits > 0) {
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            if ((network[fullBytes] & mask) != (candidate[fullBytes] & mask)) {
                return false;
            }
        }
        return true;
    }

    /** 原始配置文本。 */
    public String source() {
        return source;
    }

    @Override
    public String toString() {
        return source;
    }

    /**
     * 解析地址字面量：含 {@code ':'} 走 IPv6，否则按 IPv4 严格解析。
     */
    private static byte[] parseAddress(String text, String fullText) {
        if (text.isEmpty()) {
            throw new IllegalArgumentException("非法 CIDR（地址部分为空）：" + fullText);
        }
        if (text.indexOf(':') >= 0) {
            if (text.indexOf('%') >= 0) {
                // 带 zone id（fe80::1%eth0）的地址依赖网卡名，跨机器不可复现，一律拒绝
                throw new IllegalArgumentException("不支持带 zone id 的 IPv6 地址：" + fullText);
            }
            try {
                return normalize(InetAddress.getByName(text).getAddress());
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("非法 IPv6 地址：" + fullText);
            }
        }
        return parseIpv4(text, fullText);
    }

    /**
     * 手工解析点分十进制 IPv4。
     *
     * <p>为什么不用 {@code InetAddress.getByName}：非字面量的输入会被当成主机名做 DNS 查询；
     * 而且它对 {@code 127.1}、前导零等宽松形式也接受，不如本方法可预期。
     */
    private static byte[] parseIpv4(String text, String fullText) {
        String[] segments = text.split("\\.", -1);
        if (segments.length != 4) {
            throw new IllegalArgumentException("非法 IPv4 地址（应为 4 段）：" + fullText);
        }
        byte[] raw = new byte[4];
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.isEmpty() || segment.length() > IPV4_MAX_SEGMENT_DIGITS) {
                throw new IllegalArgumentException("非法 IPv4 地址（段 " + (i + 1) + " 长度异常）：" + fullText);
            }
            for (int j = 0; j < segment.length(); j++) {
                char c = segment.charAt(j);
                if (c < '0' || c > '9') {
                    throw new IllegalArgumentException("非法 IPv4 地址（含非数字字符）：" + fullText);
                }
            }
            int value = Integer.parseInt(segment);
            if (value > 255) {
                throw new IllegalArgumentException("非法 IPv4 地址（段 " + (i + 1) + " 超过 255）：" + fullText);
            }
            raw[i] = (byte) value;
        }
        return raw;
    }

    /**
     * 把 IPv4-mapped IPv6（{@code ::ffff:a.b.c.d}）归一化成 4 字节 IPv4。
     *
     * <p>这是双栈部署下的真实场景，不是理论问题：Tomcat 用 IPv6 socket 接受 IPv4 连接时，
     * 对端地址就是这个形态。不归一化会出现「明明配了 127.0.0.1/32，本机请求却被拒」。
     * 注意 {@code ::1}（IPv6 回环）不满足 mapped 条件，保持 16 字节，因此必须单独配。
     */
    private static byte[] normalize(byte[] raw) {
        if (raw.length != 16) {
            return raw;
        }
        for (int i = 0; i < MAPPED_PREFIX_ZERO_BYTES; i++) {
            if (raw[i] != 0) {
                return raw;
            }
        }
        if ((raw[10] & 0xFF) == 0xFF && (raw[11] & 0xFF) == 0xFF) {
            return new byte[]{raw[12], raw[13], raw[14], raw[15]};
        }
        return raw;
    }

    /** 按掩码长度把网络地址的其余位清零，使日志与比较结果稳定可读。 */
    private static byte[] maskOut(byte[] address, int prefixBits) {
        byte[] masked = address.clone();
        int fullBytes = prefixBits / 8;
        int remainingBits = prefixBits % 8;
        if (remainingBits > 0 && fullBytes < masked.length) {
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            masked[fullBytes] = (byte) (masked[fullBytes] & mask);
            fullBytes++;
        }
        for (int i = fullBytes; i < masked.length; i++) {
            masked[i] = 0;
        }
        return masked;
    }

    /** 暴露给测试的位长断言辅助（避免测试硬编码 32/128 字面量）。 */
    int bits() {
        return prefixBits;
    }

    /** IPv4 字面量的位数常量，供测试引用。 */
    public static int ipv4Bits() {
        return IPV4_BITS;
    }
}
