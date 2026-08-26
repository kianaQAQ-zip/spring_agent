package com.ecomagent.rag;

/**
 * Token 估算工具（§9.4）。
 * 中文按 len/1.5 近似（tiktoken 对 CJK 不准），拉丁字母/数字按词计。
 */
public final class TokenUtils {

    private TokenUtils() {
    }

    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4e00 && c <= 0x9fff) {
                cjk++;
            } else if (Character.isLetterOrDigit(c)) {
                other++;
            }
        }
        return (int) (cjk / 1.5 + other);
    }
}
