package com.ecomagent.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评估判分单测（§9 四维判分）。
 */
class EvalRunnerTest {

    private final EvalRunner runner = new EvalRunner();

    @Test
    void fullScorePasses() {
        EvalCase c = new EvalCase("R1", "refund", "我要退 ORD-1001",
                List.of("退款", "ORD-1001"), "REFUND");
        AgentAnswer a = new AgentAnswer("REFUND", "您的退款申请已提交，订单 ORD-1001", true, true);

        CaseScore s = runner.score(c, a);

        assertEquals(1.0, s.keyword(), 0.001);
        assertEquals(1.0, s.intent(), 0.001);
        assertEquals(1.0, s.grounding(), 0.001);
        assertEquals(1.0, s.faithfulness(), 0.001);
        assertTrue(s.pass());
    }

    @Test
    void keywordAndGroundingMissLowersScore() {
        EvalCase c = new EvalCase("R1", "refund", "q", List.of("退款", "ORD-1001"), "REFUND");
        AgentAnswer a = new AgentAnswer("REFUND", "您好，有什么可以帮您", false, false);

        CaseScore s = runner.score(c, a);

        assertEquals(0.0, s.keyword(), 0.001);
        assertFalse(s.pass());
    }

    @Test
    void loadsDefaultEvalSet() {
        List<EvalCase> cases = runner.loadDefaultCases();
        assertEquals(20, cases.size(), "默认评估集应为 20 条");
    }
}
