package com.genvict.dssad.cloud.security;

import com.genvict.dssad.cloud.config.AppProperties;
import com.genvict.dssad.cloud.support.TestProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /actuator/**} 访问控制测试（O-03 的回归防护）。
 *
 * <p>这些用例的存在意义是一句话：<b>「8080 可达」不再等于「Actuator 可读」</b>。
 * 之前 {@code AdminTokenInterceptor} 里那行 {@code startsWith("/actuator")} 是永不执行的死代码，
 * 因此本测试刻意不断言「拦截器放行了 actuator」，而是断言过滤器<b>真的拦住了外部来源</b>。
 */
@DisplayName("Actuator 访问控制")
class ActuatorAccessControlTest {

    private static final String DEFAULT_CONFIGURED_CIDR_KEY = "allowed-cidrs";

    /** 过滤器放行/拦截的结果快照。 */
    private record Result(boolean passedThrough, int status) {
    }

    /**
     * 模拟 {@code ForwardedHeaderFilter} 的行为：用 X-Forwarded-For 覆盖 {@code getRemoteAddr()}，
     * 但不改动底层请求的真实对端地址。
     *
     * <p>Spring Boot 在 {@code server.forward-headers-strategy: framework} 下注册的
     * {@code ForwardedHeaderFilter} 就是这种包装（{@code ForwardedHeaderExtractingRequest}
     * extends {@code HttpServletRequestWrapper}）。用它来验证「伪造 XFF 无法绕过白名单」。
     */
    private static final class ForwardedHeaderStyleRequest extends HttpServletRequestWrapper {

        private final String forwardedAddress;

        ForwardedHeaderStyleRequest(HttpServletRequest request, String forwardedAddress) {
            super(request);
            this.forwardedAddress = forwardedAddress;
        }

        @Override
        public String getRemoteAddr() {
            return forwardedAddress;
        }

        @Override
        public String getHeader(String name) {
            return "X-Forwarded-For".equalsIgnoreCase(name) ? forwardedAddress : super.getHeader(name);
        }
    }

    /** 执行过滤链，返回「是否放行」与响应状态码。 */
    private static Result run(ActuatorIpWhitelistFilter filter, HttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);
        FilterChain chain = (ServletRequest req, ServletResponse res) -> invoked.set(true);
        filter.doFilter(request, response, chain);
        return new Result(invoked.get(), response.getStatus());
    }

    private static MockHttpServletRequest actuatorRequestFrom(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    // ==================================================================================
    // 一、CIDR 解析与匹配
    // ==================================================================================

    @Nested
    @DisplayName("CIDR 解析与匹配")
    class CidrMatcherTests {

        @Test
        @DisplayName("单 IP 等价于 /32，只匹配自身")
        void singleIpEqualsSlash32() {
            IpCidrMatcher matcher = IpCidrMatcher.of("127.0.0.1");
            assertEquals(IpCidrMatcher.ipv4Bits(), matcher.bits(), "省略掩码时应补全为 32");
            assertTrue(matcher.matches("127.0.0.1"));
            assertFalse(matcher.matches("127.0.0.2"));
        }

        @Test
        @DisplayName("/8 按整段匹配，边界外侧不命中")
        void cidrRangeMatchesWholeSubnet() {
            IpCidrMatcher matcher = IpCidrMatcher.of("10.0.0.0/8");
            assertTrue(matcher.matches("10.0.0.1"));
            assertTrue(matcher.matches("10.255.255.255"));
            assertFalse(matcher.matches("11.0.0.1"), "/8 不应命中 11 网段");
            assertFalse(matcher.matches("9.255.255.255"), "/8 不应命中 9 网段");
        }

        @Test
        @DisplayName("非字节对齐掩码（/25）在半个字节处切分正确")
        void nonByteAlignedMask() {
            IpCidrMatcher matcher = IpCidrMatcher.of("192.168.1.0/25");
            assertTrue(matcher.matches("192.168.1.0"), "网段首地址应命中");
            assertTrue(matcher.matches("192.168.1.127"), "网段末地址应命中");
            assertFalse(matcher.matches("192.168.1.128"), "相邻网段首地址不应命中");
        }

        @Test
        @DisplayName("IPv4-mapped IPv6 归一化后可命中 IPv4 规则（双栈部署真实场景）")
        void ipv4MappedIpv6MatchesIpv4Rule() {
            IpCidrMatcher matcher = IpCidrMatcher.of("127.0.0.1/32");
            assertTrue(matcher.matches("::ffff:127.0.0.1"),
                    "Tomcat 双栈 socket 上对端地址可能是 ::ffff:127.0.0.1，必须能命中 127.0.0.1/32");
            assertFalse(matcher.matches("::ffff:8.8.8.8"));
        }

        @Test
        @DisplayName("IPv6 回环与网段")
        void ipv6LoopbackAndRange() {
            assertTrue(IpCidrMatcher.of("::1").matches("::1"));
            assertTrue(IpCidrMatcher.of("fe80::/10").matches("fe80::1"));
            assertFalse(IpCidrMatcher.of("fe80::/10").matches("2001:db8::1"));
        }

        @Test
        @DisplayName("地址族不混用：IPv4 规则不匹配 IPv6 回环")
        void addressFamiliesDoNotLeak() {
            assertFalse(IpCidrMatcher.of("127.0.0.1/32").matches("::1"),
                    "::1 与 127.0.0.1 是两个地址，必须分别配置");
        }

        @Test
        @DisplayName("0.0.0.0/0 才代表放开全部（放开的写法应当显眼）")
        void allZeroCidrOpensEverything() {
            IpCidrMatcher matcher = IpCidrMatcher.of("0.0.0.0/0");
            assertTrue(matcher.matches("8.8.8.8"));
            assertTrue(matcher.matches("127.0.0.1"));
        }

        @ParameterizedTest(name = "非法配置应抛异常：{0}")
        @ValueSource(strings = {
                "300.1.1.1",          // 段超过 255
                "10.0.0.0/33",        // 掩码超出 IPv4 位宽
                "10.0.0.0/x",         // 掩码不是整数
                "10.0.0",             // 段数不足
                "10.0.0.0.0",         // 段数过多
                "10.0.0.-1",          // 出现负号
                "not-an-ip",          // 完全不是地址
                "fe80::1%eth0",       // 带 zone id，跨机器不可复现
                "::1/129",            // 掩码超出 IPv6 位宽
        })
        @DisplayName("非法 CIDR 一律 fail-fast（不允许静默降级）")
        void invalidCidrFailsFast(String invalid) {
            assertThrows(IllegalArgumentException.class, () -> IpCidrMatcher.of(invalid),
                    "非法配置必须在启动期暴露，而不是变成『以为配了其实没生效』");
        }

        @Test
        @DisplayName("来源地址不可解析时判为不匹配（失败方向必须是拒绝）")
        void unparseableCandidateDoesNotMatch() {
            IpCidrMatcher matcher = IpCidrMatcher.of("127.0.0.1/32");
            assertFalse(matcher.matches("not-an-ip"));
            assertFalse(matcher.matches(""));
            assertFalse(matcher.matches(null));
            assertFalse(matcher.matches("127.0.0.1:12345"), "带端口的字符串不应被当作地址");
        }
    }

    // ==================================================================================
    // 二、过滤器行为
    // ==================================================================================

    @Nested
    @DisplayName("过滤器行为")
    class FilterBehaviorTests {

        private final ActuatorIpWhitelistFilter defaultFilter =
                new ActuatorIpWhitelistFilter(TestProperties.defaults());

        @Test
        @DisplayName("默认配置放行回环地址（IPv4 与 IPv6）")
        void loopbackAllowed() throws Exception {
            assertTrue(run(defaultFilter, actuatorRequestFrom("127.0.0.1")).passedThrough());
            assertTrue(run(defaultFilter, actuatorRequestFrom("::1")).passedThrough());
        }

        @Test
        @DisplayName("默认配置拒绝外部地址，且返回 404 而非 403")
        void externalAddressDeniedAsNotFound() throws Exception {
            Result result = run(defaultFilter, actuatorRequestFrom("8.8.8.8"));
            assertFalse(result.passedThrough(), "外部来源不得进入过滤链");
            assertEquals(404, result.status(), "必须伪装成『路径不存在』，403 会确认端点存在");
        }

        @Test
        @DisplayName("★ 伪造 X-Forwarded-For: 127.0.0.1 无法绕过白名单")
        void rejectsSpoofedForwardedHeader() throws Exception {
            // 真实 TCP 对端是 8.8.8.8，但攻击者伪造 XFF 与 getRemoteAddr() 为 127.0.0.1
            MockHttpServletRequest raw = actuatorRequestFrom("8.8.8.8");
            HttpServletRequest spoofed = new ForwardedHeaderStyleRequest(raw, "127.0.0.1");

            Result result = run(defaultFilter, spoofed);

            assertFalse(result.passedThrough(),
                    "server.forward-headers-strategy=framework 会信任 XFF，"
                            + "若直接读 getRemoteAddr() 则任何人加一个头即可绕过白名单");
            assertEquals(404, result.status());
        }

        @Test
        @DisplayName("真实对端在白名单内时，即便 XFF 指向外部也照常放行（避免误伤）")
        void realPeerWinsOverForwardedHeader() throws Exception {
            MockHttpServletRequest raw = actuatorRequestFrom("127.0.0.1");
            HttpServletRequest forwarded = new ForwardedHeaderStyleRequest(raw, "8.8.8.8");

            assertTrue(run(defaultFilter, forwarded).passedThrough(),
                    "判定依据是真实对端，不应被 XFF 反向影响");
        }

        @Test
        @DisplayName("多层包装也能拆到最内层原始请求")
        void deeplyNestedWrappersAreUnwrapped() throws Exception {
            MockHttpServletRequest raw = actuatorRequestFrom("8.8.8.8");
            HttpServletRequest doubleWrapped = new ForwardedHeaderStyleRequest(
                    new ForwardedHeaderStyleRequest(raw, "127.0.0.1"), "127.0.0.1");

            assertFalse(run(defaultFilter, doubleWrapped).passedThrough(),
                    "包装层数不固定（ForwardedHeaderFilter + CachedBodyRequestWrapper 等），必须全部穿透");
        }

        @Test
        @DisplayName("★ 空白名单拒绝一切来源（fail-closed，不会退化成放行）")
        void emptyWhitelistRejectsEverything() throws Exception {
            ActuatorIpWhitelistFilter closed = new ActuatorIpWhitelistFilter(new String[0]);
            assertFalse(run(closed, actuatorRequestFrom("127.0.0.1")).passedThrough(),
                    "未配置白名单必须等于『全拒』；若这里放行，说明白名单语义被写反了");
            assertEquals(404, run(closed, actuatorRequestFrom("10.1.2.3")).status());
        }

        @Test
        @DisplayName("自定义网段生效（模拟容器网络内 Nginx 探活）")
        void customSubnetAllowsContainerPeer() throws Exception {
            ActuatorIpWhitelistFilter docker =
                    new ActuatorIpWhitelistFilter(new String[]{"127.0.0.1/32", "::1/128", "172.16.0.0/12"});
            assertTrue(run(docker, actuatorRequestFrom("172.17.0.5")).passedThrough());
            assertFalse(run(docker, actuatorRequestFrom("172.32.0.5")).passedThrough());
        }

        @Test
        @DisplayName("非法配置导致构造失败（启动即拦截，而非运行期静默失效）")
        void invalidConfiguredCidrFailsFast() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ActuatorIpWhitelistFilter(new String[]{"10.0.0.1/99"}));
        }

        @Test
        @DisplayName("非 Actuator 路径完全不受本过滤器影响")
        void nonActuatorPathsUntouched() throws Exception {
            MockHttpServletRequest api = new MockHttpServletRequest("GET", "/api/v1/monitor/health-check");
            api.setRemoteAddr("8.8.8.8");
            assertTrue(run(defaultFilter, api).passedThrough(),
                    "业务接口有各自的鉴权（令牌/签名），不能被 Actuator 白名单误拦");

            MockHttpServletRequest enterprise = new MockHttpServletRequest("POST", "/enterprise/api/v1/event");
            enterprise.setRemoteAddr("8.8.8.8");
            assertTrue(run(defaultFilter, enterprise).passedThrough());
        }

        @Test
        @DisplayName("配置绑定：application.yml 中的键能被 AppProperties 正确解析")
        void configurationBindsFromYaml() throws IOException {
            MutablePropertySources sources = new MutablePropertySources();
            new YamlPropertySourceLoader()
                    .load("application", new ClassPathResource("application.yml"))
                    .forEach(sources::addLast);
            Binder binder = new Binder(ConfigurationPropertySources.from(sources));

            AppProperties bound = binder.bind("dssad", Bindable.of(AppProperties.class))
                    .orElseThrow(() -> new AssertionError("dssad 配置绑定失败"));

            List<String> cidrs = List.of(bound.security().actuator().allowedCidrs());
            assertEquals(List.of("127.0.0.1/32", "::1/128"), cidrs,
                    "yml 中的键名必须与 AppProperties.Security.Actuator 对得上，否则绑定会静默用上默认值");

            String yaml = new ClassPathResource("application.yml").getContentAsString(StandardCharsets.UTF_8);
            assertTrue(yaml.contains(DEFAULT_CONFIGURED_CIDR_KEY),
                    "白名单必须是 application.yml 中显式可见的配置项，而不是只藏在注解默认值里");
        }

        @Test
        @DisplayName("AppProperties 构造出的过滤器与 yml 默认值等效")
        void propertiesConstructedFilterMatchesDefaults() throws Exception {
            ActuatorIpWhitelistFilter fromProps =
                    new ActuatorIpWhitelistFilter(TestProperties.withActuatorCidrs("10.0.0.0/8"));
            assertTrue(run(fromProps, actuatorRequestFrom("10.9.9.9")).passedThrough());
            assertFalse(run(fromProps, actuatorRequestFrom("127.0.0.1")).passedThrough(),
                    "显式配置会整体替换默认值，回环地址不再自动放行（需自行列入）");
        }
    }
}
