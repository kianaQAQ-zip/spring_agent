package com.ecomagent.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 租户拦截器（§9 多租户接缝）：从请求头 {@code X-Tenant-Id} 注入 {@link TenantContext}，
 * 请求结束后清理。未携带时保持默认 {@code default}（单商户）。
 */
@Component
public class TenantInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Tenant-Id";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String tenant = request.getHeader(HEADER);
        if (tenant != null && !tenant.isBlank()) {
            TenantContext.set(tenant);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        TenantContext.clear();
    }
}
