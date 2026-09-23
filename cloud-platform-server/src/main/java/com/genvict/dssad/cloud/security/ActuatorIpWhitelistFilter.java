package com.genvict.dssad.cloud.security;

import com.genvict.dssad.cloud.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.web.servlet.WebMvcEndpointHandlerMapping;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Actuator 端点（{@code /actuator/**}）的来源 IP 白名单过滤器（<b>fail-closed</b>）。
 *
 * <h3>为什么需要它</h3>
 * {@code /actuator/**} 不受 {@link AdminTokenInterceptor} 保护 —— 该拦截器只注册在
 * {@code /api/v1/**} 上（见 {@code WebConfig}），所以它的
 * {@code uri.startsWith("/actuator")} 分支实际是<b>永不执行的死代码</b>。
 * 结果是：只要 8080 端口可达，Actuator 上的 {@code /env}、{@code /configprops}、
 * {@code /heapdump}、{@code /threaddump} 就是<b>完全无鉴权</b>的。
 *
 * <p>生产侧虽由 Nginx 把 {@code /actuator/} 收敛为 404，但那只是外围一层：
 * 容器网络内的其它容器、同宿主机进程、误开的端口映射、以及任何绕过 Nginx 的直连
 * 都能穿过去。安全边界要落在应用自己身上，这就是本过滤器存在的理由。
 *
 * <h3>为什么用 Filter 而不是 Interceptor</h3>
 * Actuator 端点由 {@link WebMvcEndpointHandlerMapping} 在 DispatcherServlet 内部处理，
 * 过滤器在 MVC 之前执行，覆盖面更明确（不依赖 handler mapping 的注册细节），
 * 也避免与 {@link AdminTokenInterceptor} 的路径匹配产生耦合。
 *
 * <h3>为什么返回 404 而不是 403</h3>
 * 403 等于告诉未授权来源「这个端点存在，只是你无权访问」，为攻击者确认指纹提供便利。
 * 404 与「路径不存在」不可区分 —— 内部请求得到 404，网关上、业务代码上也得到 404，不泄露任何信息。
 *
 * <h3>为什么不能读 {@code request.getRemoteAddr()} 了事</h3>
 * {@code server.forward-headers-strategy: framework} 会让 Spring 注册
 * {@code ForwardedHeaderFilter}，它<b>用 X-Forwarded-For 覆盖 getRemoteAddr()</b>。
 * 若直接读该方法，任何人发一个 {@code X-Forwarded-For: 127.0.0.1} 就能伪造来源、
 * 把自己的外部地址伪装成白名单内的回环地址。
 * 因此本类先逐层拆开 ServletRequest 包装链，取<b>最内层原始请求</b>的 TCP 对端地址，
 * 完全无视 XFF（对应单测 {@code rejectsSpoofedForwardedHeader}）。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class ActuatorIpWhitelistFilter extends OncePerRequestFilter {

    /** Actuator 路径前缀。 */
    public static final String PATH_PREFIX = "/actuator";

    /** 拆包装链的循环保护上限（正常只有 1~3 层，防止异常实现造成死循环）。 */
    private static final int MAX_WRAPPER_DEPTH = 16;

    /** 允许访问的来源网段；**为空表示拒绝所有来源**。 */
    private final List<IpCidrMatcher> allowed;

    /**
     * Spring 注入用的构造器。
     *
     * <p>⚠️ 本类有<b>两个</b>构造器（另一个供单测直接传网段数组），
     * 若不显式标注 {@link Autowired}，Spring 会因「无法在多个构造器间做选择」而退回到
     * 无参构造器 —— 后者不存在，于是整个上下文启动失败（表现为所有 {@code @SpringBootTest}
     * 用例一起挂掉）。这个坑在 {@code MqttChannelProbe} 上踩过一次，此处保留注释以作警示。
     */
    @Autowired
    public ActuatorIpWhitelistFilter(AppProperties properties) {
        this(properties.security().actuator().allowedCidrs());
    }

    /**
     * 直接指定网段的构造器（供单元测试使用，也便于在其它上下文中复用）。
     *
     * @param allowedCidrs 允许的来源网段；非法项抛 {@link IllegalArgumentException}（启动即失败）
     */
    public ActuatorIpWhitelistFilter(String[] allowedCidrs) {
        List<IpCidrMatcher> parsed = IpCidrMatcher.parseAll(allowedCidrs);
        if (parsed.isEmpty()) {
            log.warn("[安全] /actuator/** 来源白名单为空 —— 将拒绝所有来源（含回环地址），"
                    + "Actuator 的 HTTP 访问已彻底关闭；如需探活请改用 /api/v1/monitor/*");
        } else {
            log.info("[安全] /actuator/** 来源 IP 白名单已启用：允许 {} 个网段 {}，其余一律 404",
                    parsed.size(), parsed);
        }
        this.allowed = List.copyOf(parsed);
    }

    /** 只处理 Actuator 路径，其余请求零开销穿过。 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String clientIp = originalRemoteAddress(request);
        if (isAllowed(clientIp)) {
            filterChain.doFilter(request, response);
            return;
        }
        log.warn("[安全] 拒绝访问 Actuator：来源 IP={} uri={} forwardedFor={}",
                clientIp, request.getRequestURI(), request.getHeader("X-Forwarded-For"));
        denyAsNotFound(response);
    }

    /**
     * 取<b>未被 X-Forwarded-For 覆盖</b>的 TCP 对端地址。
     *
     * <p>逐层拆开 {@link HttpServletRequestWrapper} 直到最内层原始请求
     * （Tomcat 下是 {@code RequestFacade}）。签名/内容包装器也一并被穿透，
     * 因此本方法测到的始终是真实连接来源。
     *
     * @param request 当前请求（可能已被多层包装）
     * @return TCP 对端 IP 字面量
     */
    static String originalRemoteAddress(HttpServletRequest request) {
        ServletRequest current = request;
        int depth = 0;
        while (current instanceof HttpServletRequestWrapper wrapper && depth++ < MAX_WRAPPER_DEPTH) {
            ServletRequest next = wrapper.getRequest();
            if (next == null || next == current) {
                break;
            }
            current = next;
        }
        return current.getRemoteAddr();
    }

    /** 来源是否命中白名单（**空白名单恒为 false**）。 */
    boolean isAllowed(String clientIp) {
        for (IpCidrMatcher matcher : allowed) {
            if (matcher.matches(clientIp)) {
                return true;
            }
        }
        return false;
    }

    /** 以「资源不存在」回应未授权来源，不写入任何可识别信息。 */
    private void denyAsNotFound(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.NOT_FOUND.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        // 不返回平台统一响应体：那会暴露「这是本平台的一个受保护端点」这一事实
        response.getWriter().write("{\"status\":404,\"error\":\"Not Found\"}");
        response.getWriter().flush();
    }
}
