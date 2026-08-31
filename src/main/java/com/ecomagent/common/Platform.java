package com.ecomagent.common;

import java.util.Arrays;

/**
 * 电商平台（渠道）枚举。
 *
 * <p>与 {@link TenantContext} 是<b>正交的两个维度</b>：
 * tenant 指"哪个商家"，platform 指"从哪个渠道来的咨询"。
 * 业务前提是单商家多平台，所以 tenant 恒为 default，platform 才是统计分组键。
 *
 * <p>取值来源（Q2 人工标注）：客服在会话界面选择后由 {@code X-Platform-Id} 请求头带入；
 * 未携带时落 {@link #UNKNOWN}，不影响主流程，只是该条不进平台分布统计。
 */
public enum Platform {

    TAOBAO("taobao", "淘宝"),
    JD("jd", "京东"),
    PDD("pdd", "拼多多"),
    DOUYIN("douyin", "抖音"),
    KUAISHOU("kuaishou", "快手"),
    WECHAT("wechat", "微信小店"),
    OFFICIAL("official", "官方商城"),
    UNKNOWN("unknown", "未标注");

    private final String code;
    private final String label;

    Platform(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    /** 从请求头解析，未识别一律落 UNKNOWN——不因脏输入抛异常。 */
    public static Platform of(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        String v = value.trim().toLowerCase();
        return Arrays.stream(values())
                .filter(p -> p.code.equals(v))
                .findFirst()
                .orElse(UNKNOWN);
    }
}
