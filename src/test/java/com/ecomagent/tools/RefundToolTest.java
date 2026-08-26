package com.ecomagent.tools;

import com.ecomagent.agent.ConfirmationService;
import com.ecomagent.agent.PendingAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 需确认工具拦截测试（M4）：@ConfirmRequired 工具不真执行副作用，而是落 pending 并返回 PENDING。
 */
@ExtendWith(MockitoExtension.class)
class RefundToolTest {

    @Mock
    private ConfirmationService confirmationService;

    @Test
    void refundCreatesPendingInsteadOfExecuting() {
        PendingAction pending = new PendingAction("p1", "conv-1", "default", "refund",
                "{\"orderId\":\"ORD-1001\"}", PendingAction.STATUS_PENDING, "key", null, null,
                null, null, Instant.now(), Instant.now().plusSeconds(300));
        when(confirmationService.request(eq("conv-1"), eq("refund"), anyMap())).thenReturn(pending);

        RefundTool tool = new RefundTool(confirmationService);
        ToolContext ctx = new ToolContext(Map.of("conversationId", "conv-1"));

        String result = tool.refund("ORD-1001", "质量问题", 199.0, ctx);

        assertTrue(result.contains("PENDING_CONFIRMATION"), "需确认工具应返回待确认标记: " + result);
        assertTrue(result.contains("p1"), "返回结果应携带 pendingId");
        // 未真执行副作用：仅落 pending，不应有任何执行动作（此处验证 request 被调用一次）
        verify(confirmationService).request(eq("conv-1"), eq("refund"), anyMap());
    }
}
