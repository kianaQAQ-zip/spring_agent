package com.ecomagent.common;

/**
 * 平台（渠道）上下文，与 {@link TenantContext} 平行。
 *
 * <p>租户回答"哪个商家"，平台回答"从哪个渠道来"。单商家多平台场景下
 * tenant 恒为 {@code default}，platform 才是统计分组维度，两者不能合并。
 *
 * <p>取值受控（只能是 {@link Platform} 枚举），用于拼装 SQL 参数与向量库
 * filterExpression 均无注入风险。
 */
public final class PlatformContext {

    private static final ThreadLocal<Platform> CURRENT =
            ThreadLocal.withInitial(() -> Platform.UNKNOWN);

    private PlatformContext() {
    }

    public static Platform get() {
        Platform v = CURRENT.get();
        return v == null ? Platform.UNKNOWN : v;
    }

    public static String code() {
        return get().code();
    }

    public static void set(Platform platform) {
        CURRENT.set(platform == null ? Platform.UNKNOWN : platform);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
