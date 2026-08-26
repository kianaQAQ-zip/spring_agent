package com.ecomagent.agent;

/**
 * 规则信号检测结果（§8.8.1）。
 */
public record Signal(
        Intent intent,
        String orderId,
        String emotion,
        int orderIdCount,
        boolean hasPronoun) {

    public static Signal empty() {
        return new Signal(Intent.UNKNOWN, null, null, 0, false);
    }
}
