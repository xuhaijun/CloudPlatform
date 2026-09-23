package com.genvict.dssad.cloud.config;

import com.genvict.dssad.cloud.security.AdminTokenInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：拦截器与跨域。
 *
 * <p>跨域策略说明：前端开发态（Vite dev server，默认 5173 端口）与后端（8080）不同源，
 * 需要 CORS。生产环境由 Nginx 同源反向代理（{@code /api} 转发到后端），
 * 因此这里配置的允许来源列表在开发态使用、生产态可收紧为空。
 *
 * <p>注意：{@code allowCredentials} 与 {@code allowedOrigins("*")} 不能共存
 * （浏览器规范限制），故使用 {@code allowedOriginPatterns}。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AdminTokenInterceptor adminTokenInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminTokenInterceptor)
                // 仅保护平台自身的管理接口；对外接口由签名过滤器保护
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns(
                        "/api/v1/auth/login",
                        // 两个探针端点都必须免令牌：编排/监控系统没有账号口令，
                        // 且探针在实例不健康时正是最需要能访问的时候（带令牌反而要求先能登录）
                        "/api/v1/monitor/health-check",
                        "/api/v1/monitor/mqtt-health",
                        "/api/v1/simulator/**",
                        "/error");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders(AdminTokenInterceptor.TOKEN_HEADER)
                .allowCredentials(true)
                .maxAge(3600);

        // 对外接口按监管平台约定只开放 POST + JSON，不开放浏览器跨域
        registry.addMapping("/enterprise/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("POST")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
