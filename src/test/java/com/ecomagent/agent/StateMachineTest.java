package com.ecomagent.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 意图状态机 + 主动澄清单测（§8.6）。
 */
class StateMachineTest {

    private final StateMachine stateMachine = new StateMachine();

    @Test
    void multipleOrdersNeedClarification() {
        Signal s = new Signal(Intent.REFUND, "ORD-1001", null, 2, false);
        Decision d = stateMachine.decide(null, null, s);
        assertTrue(d.needsClarification(), "多订单口述冲突应澄清");
    }

    @Test
    void pronounWithoutOrderNeedsClarification() {
        Signal s = new Signal(Intent.REFUND, null, null, 0, true);
        Decision d = stateMachine.decide(null, null, s);
        assertTrue(d.needsClarification(), "代词指代但无订单号应澄清");
    }

    @Test
    void compatibleTransitionProceeds() {
        Signal s = new Signal(Intent.REFUND, "ORD-1001", null, 1, false);
        Decision d = stateMachine.decide("ORDER_QUERY", "ORD-1001", s);
        assertFalse(d.needsClarification(), "订单查询→退款是兼容转移，应继续");
    }
}
