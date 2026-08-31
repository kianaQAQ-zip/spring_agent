package com.ecomagent.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 上下文拦截器（§9 多租户 + 多渠道接缝）：从请求头注入 {@link TenantContext} 与
 * {@link PlatformContext}，请求结束后清理。
 *
 * <ul>
 *   <li>{@code X-Tenant-Id} —— 哪个商家，缺省 {@code default}</li>
 *   <li>{@code X-Platform-Id} —— 哪个渠道（Q2 人工标注），缺省 {@code unknown}</li>
 * </ul>
 *
 * <p>两个维度正交，不能互相推断：同一个商家在淘宝和京东的店，咨询要分开统计。
 */
@Component
public class ContextInterceptor implements HandlerInterceptor {

    public static final String TENANT_HEADER = "X-Tenant-Id";
    public static final String PLATFORM_HEADER = "X-Platform-Id";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String tenant = request.getHeader(TENANT_HEADER);
        if (tenant != null && !tenant.isBlank()) {
            TenantContext.set(tenant);
        }
        // 平台优先取请求头；SSE 用 EventSource 加不了自定义头，降级读 query 参数
        String platform = request.getHeader(PLATFORM_HEADER);
        if (platform == null || platform.isBlank()) {
            platform = request.getParameter("platform");
        }
        PlatformContext.set(Platform.of(platform));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        TenantContext.clear();
        PlatformContext.clear();
    }
}
