package com.ecomagent.order;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 订单查询视图（只读投影）。
 *
 * <p>{@code buyerName} / {@code buyerPhone} 属 PII，出库后在 Tool 层脱敏，
 * 不在此 record 内部处理——保持 Repository 纯粹，脱敏策略由调用方决定。
 */
public record OrderRecord(
        String orderId,
        String platform,
        String buyerName,
        String buyerPhone,
        String status,
        BigDecimal amount,
        String itemTitle,
        int quantity,
        String address,
        String carrier,
        String trackingNo,
        String latestTrace,
        Instant createdAt) {

    public boolean hasLogistics() {
        return carrier != null && !carrier.isBlank();
    }
}
