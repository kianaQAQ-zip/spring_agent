package com.ecomagent.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则信号检测单测（§8.8.1）：否定窗口 / 订单号抽取 / 代词 / 多订单。
 */
class SignalDetectorTest {

    private final SignalDetector detector = new SignalDetector();

    @Test
    void negationWindowPreventsFalseOrderIntent() {
        Signal s = detector.detect("心情不好但订单没问题");
        assertNotEquals(Intent.ORDER_QUERY, s.intent(), "否定窗口应防止误触发订单意图");
    }

    @Test
    void detectsRefundAndOrderId() {
        Signal s = detector.detect("我要退 ORD-1001 的耳机");
        assertEquals(Intent.REFUND, s.intent());
        assertEquals("ORD-1001", s.orderId());
    }

    @Test
    void detectsMultipleOrders() {
        Signal s = detector.detect("ORD-1001 和 ORD-2002 都退");
        assertEquals(2, s.orderIdCount(), "应识别多个订单号");
    }

    @Test
    void detectsPronoun() {
        Signal s = detector.detect("这个能退吗");
        assertTrue(s.hasPronoun());
    }
}
