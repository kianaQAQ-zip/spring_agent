package com.ecomagent.common;

/**
 * 租户上下文（M3 引入，M9 收口多租户接缝）。
 *
 * <p>当前单商户自用，默认 {@code default}。M9 将通过拦截器从请求头/会话注入真实 tenant_id，
 * 所有向量检索/状态/对话查询统一经此处取租户，保证隔离。
 *
 * <p>tenant 取值为受控常量（非用户自由输入），用于拼装向量库 filterExpression 安全无注入风险。
 */
public final class TenantContext {

    public static final String DEFAULT_TENANT = "default";

    private static final ThreadLocal<String> CURRENT = ThreadLocal.withInitial(() -> DEFAULT_TENANT);

    private TenantContext() {
    }

    public static String get() {
        String v = CURRENT.get();
        return (v == null || v.isBlank()) ? DEFAULT_TENANT : v;
    }

    public static void set(String tenant) {
        CURRENT.set(tenant == null || tenant.isBlank() ? DEFAULT_TENANT : tenant);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
