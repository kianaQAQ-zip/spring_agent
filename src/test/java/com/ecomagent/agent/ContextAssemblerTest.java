package com.ecomagent.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文装配单测（§8.8.2）：token 预算 + 状态分区。
 */
class ContextAssemblerTest {

    private final ContextAssembler assembler = new ContextAssembler();

    private Document doc(String docId, int tokens) {
        return new Document("内容", Map.of("doc_id", docId, "chunk_index", 0, "token_count", tokens));
    }

    @Test
    void respectsTokenBudget() {
        List<Document> selected = assembler.selectDocuments(
                List.of(doc("d1", 100), doc("d2", 100), doc("d3", 100)), 250);
        assertTrue(selected.size() <= 2, "token 预算应限制选中文档数量");
    }

    @Test
    void assemblesStateAndNumberedDocs() {
        SessionState state = new SessionState("s1", "default", "REFUND", "ORD-1001", null, 0, Instant.now());
        String prompt = assembler.assemble(state, List.of(doc("d1", 10)));

        assertTrue(prompt.contains("REFUND"), "应装配状态意图");
        assertTrue(prompt.contains("ORD-1001"), "应装配状态订单号");
        assertTrue(prompt.contains("[1]"), "知识片段应编号 [n]");
    }
}
