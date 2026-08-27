package com.ecomagent.common;

import java.util.regex.Pattern;

/**
 * PII 脱敏工具（M1 骨架，§5 双重脱敏之"输出/入库脱敏"）。
 * 当前覆盖：手机号、身份证、邮箱。后续可扩展地址、订单号等。
 * 仅做正则掩码，不依赖外部服务；日志脱敏复用同一方法。
 */
public final class PiiMaskUtil {

    // 手机号：138****8000
    private static final Pattern PHONE = Pattern.compile("(1[3-9]\\d)\\d{4}(\\d{4})");
    // 身份证：保留前6后4，中间打码
    private static final Pattern ID_CARD = Pattern.compile("(\\d{6})\\d{8}(\\d{4})");
    // 邮箱：保留首字符与域名
    private static final Pattern EMAIL = Pattern.compile("([a-zA-Z0-9._+])[a-zA-Z0-9._+]*(@[a-zA-Z0-9.]+)");
    // 银行卡：保留前4后4，中间打码（16-19 位）
    private static final Pattern BANK_CARD = Pattern.compile("(\\d{4})\\d{8,11}(\\d{4})");

    private PiiMaskUtil() {
    }

    /**
     * 三道缝统一入口（§5）：入库脱敏 / 输出脱敏 / 日志脱敏均调此方法。
     * 纯正则掩码，不依赖外部服务，可安全用于日志与 trace 输出。
     */
    public static String mask(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String s = PHONE.matcher(text).replaceAll("$1****$2");
        s = ID_CARD.matcher(s).replaceAll("$1********$2");
        s = BANK_CARD.matcher(s).replaceAll("$1****$2");
        s = EMAIL.matcher(s).replaceAll("$1***$2");
        return s;
    }
}
