package com.genvict.dssad.cloud.ratelimit;

import com.fasterxml.jackson.databind.JsonNode;
import com.genvict.dssad.cloud.common.api.ApiResponse;
import com.genvict.dssad.cloud.common.api.ErrorCode;
import com.genvict.dssad.cloud.common.util.JsonUtils;
import com.genvict.dssad.cloud.security.CachedBodyRequestWrapper;
import com.genvict.dssad.cloud.security.EnterpriseSignatureFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 企业云端对外接口（{@code /enterprise/api/v1/**}）的<b>单车辆</b>限流过滤器
 * （文档 9.1：HTTP 100 次/分钟·车）。
 *
 * <h2>背景（F-01，为什么这个过滤器必须存在）</h2>
 * {@link RateLimiter} 自实现起，{@code tryAcquireHttp} 在整条请求链路上<b>零调用</b>：
 * 配置写着 {@code enabled: true}、监控端点 {@code /api/v1/monitor/cache} 还对外返回
 * {@code rateLimit.enabled=true}，而实际一个请求都不限（实测同 VIN 连打 130 次全部放行）。
 * 本过滤器把这条断链补上——这也是本项目「声明存在但从未接线」缺陷族
 * （{@code P-02} / {@code E-01} / {@code O-03} 同族）的第 6 例。
 *
 * <h2>为什么挂在 {@code EnterpriseSignatureFilter}（order +10）之后（+11）</h2>
 * <ul>
 *   <li>限流只针对<b>通过签名</b>的请求：伪造/重放请求在签名层已被拒，
 *       不应也无法消耗某辆车的正常配额；</li>
 *   <li>进入本过滤器时请求已被签名过滤器包装为 {@code CachedBodyRequestWrapper}，
 *       可直接重复读取 body 取 VIN，<b>不需要也不能再包一层</b>（重复包装会
 *       让下游 Controller 读到双层包装对象，虽能工作但徒增一层无意义的内存拷贝）。</li>
 * </ul>
 *
 * <h2>VIN 提取与失败方向（两处刻意 fail-open）</h2>
 * <ul>
 *   <li>body 非 JSON / 解析失败 / 无 {@code vin} 字段 / VIN 为空白 → <b>放行</b>：
 *       限流键是车辆，取不到键就无从限起；「单企业」维度按详细设计 6.4 明确<b>不限</b>
 *       （依赖上游自有约束与签名校验）。签名过滤器已确保请求来自合法企业，
 *       所以这不是防护缺口，而是限流语义的边界；</li>
 *   <li>{@code StateStore} 异常 → {@link RateLimiter} 内部已降级放行
 *       （宁可短暂超限，不能把正常业务打死）。</li>
 * </ul>
 *
 * <p>媒体上传（multipart）与签名过滤器同款跳过：200MB 二进制不进内存解析，
 * 该接口的防滥用由「只能对平台已请求过的事件上传」的 MQTT 侧授权约束兜底。
 *
 * <h2>拒绝响应契约（接口文档 7.1）</h2>
 * HTTP 200 + body {@code code=4001}，与 {@code EnterpriseSignatureFilter} 的错误响应
 * 同构——<b>不用 HTTP 429</b>：对端（监管平台/企业侧）按 {@code body.code} 解析，
 * 全部对外接口保持「HTTP 200 不代表成功，业务码在 body」的单一语义，
 * 响应头附带 {@code Retry-After: 60}（限流窗口为 1 分钟）供遵守 HTTP 语义的客户端退避。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 11)
public class EnterpriseRateLimitFilter extends OncePerRequestFilter {

    /** 对外接口路径前缀（与 {@code EnterpriseSignatureFilter#PATH_PREFIX} 一致）。 */
    public static final String PATH_PREFIX = EnterpriseSignatureFilter.PATH_PREFIX;

    /** 限流窗口（秒），用于 {@code Retry-After} 头。 */
    private static final long WINDOW_SECONDS = 60;

    private final RateLimiter rateLimiter;

    public EnterpriseRateLimitFilter(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    /**
     * 与 {@code EnterpriseSignatureFilter#shouldNotFilter} 保持一致：
     * 前缀外的路径（平台自身 /api/v1、actuator 等）与 multipart 上传不限流。
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
        // 期望上游（签名过滤器）已把请求包装为可重复读；若因装配顺序变化而未包装，
        // 这里**自行包装**而不是跳过判定 —— 否则限流会随过滤器顺序调整而「静默消失」，
        // 这正是本项目反复出现的「声明存在但从未接线」失效模式，必须在这里堵死。
        // 重复包装的代价仅是一次小 JSON body 的内存拷贝（multipart 已在 shouldNotFilter 排除）。
        CachedBodyRequestWrapper wrapped = request instanceof CachedBodyRequestWrapper already
                ? already : new CachedBodyRequestWrapper(request);
        String vin = extractVin(wrapped);
        // 取不到 VIN：无法按车辆限流，放行（签名过滤器已保证请求来自合法企业）
        if (vin == null) {
            filterChain.doFilter(wrapped, response);
            return;
        }
        if (rateLimiter.tryAcquireHttp(vin)) {
            filterChain.doFilter(wrapped, response);
            return;
        }
        log.warn("[限流] 拒绝企业侧请求 uri={} vin={} 限额={} 次/分钟",
                request.getRequestURI(), vin, rateLimiter.config().httpPerMinute());
        response.setHeader("Retry-After", String.valueOf(WINDOW_SECONDS));
        writeLimitExceeded(response);
    }

    /**
     * 从请求体 JSON 中提取 {@code vin} 字段。
     *
     * <p>任何解析失败都返回 {@code null}（放行）：限流是<b>保护性旁路</b>，
     * 不能因为一个畸形 body 让本应到达业务层的请求变成 500——
     * 畸形请求若真是攻击，签名校验与参数校验（{@code @Valid}）会各自拦下它。
     */
    private String extractVin(CachedBodyRequestWrapper request) {
        try {
            String body = request.getBodyAsString();
            if (!StringUtils.hasText(body)) {
                return null;
            }
            JsonNode vin = JsonUtils.readTree(body).get("vin");
            if (vin == null || vin.isNull()) {
                return null;
            }
            String value = vin.asText();
            return StringUtils.hasText(value) ? value.trim() : null;
        } catch (RuntimeException e) {
            log.debug("[限流] 请求体解析失败，跳过限流判定 uri={} 原因={}",
                    request.getRequestURI(), e.getMessage());
            return null;
        }
    }

    /** 拒绝响应：HTTP 200 + body 4001（接口文档 7.1 契约，与签名过滤器错误响应同构）。 */
    private void writeLimitExceeded(HttpServletResponse response) throws IOException {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(JsonUtils.toJson(ApiResponse.fail(ErrorCode.RATE_LIMITED,
                "请求过于频繁，已触发限流（" + rateLimiter.config().httpPerMinute() + " 次/分钟·车）")));
        response.getWriter().flush();
    }
}
