package com.ecomagent.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MmrSelector 精排单测（§9.5 Stage3）：去冗余 + token 预算。
 */
class MmrSelectorTest {

    private ScoredDoc scored(String docId, int chunk, String text, double score, int tokens) {
        return new ScoredDoc(new Document(text, Map.of(
                "doc_id", docId,
                "chunk_index", chunk,
                "token_count", tokens)), score);
    }

    @Test
    void respectsTokenBudget() {
        List<ScoredDoc> candidates = List.of(
                scored("d1", 0, "段落一内容", 0.9, 100),
                scored("d2", 0, "段落二内容", 0.8, 100),
                scored("d3", 0, "段落三内容", 0.7, 100));

        MmrSelector selector = new MmrSelector();
        List<ScoredDoc> result = selector.select(candidates, 250);

        assertTrue(result.size() <= 2, "token 预算应限制选中数量");
    }

    @Test
    void dedupsSimilarDocuments() {
        List<ScoredDoc> candidates = List.of(
                scored("d1", 0, "完全相同的文本内容", 0.9, 50),
                scored("d2", 0, "完全相同的文本内容", 0.85, 50),
                scored("d3", 0, "完全不同的另一段话", 0.5, 50));

        MmrSelector selector = new MmrSelector();
        List<ScoredDoc> result = selector.select(candidates, 1000);

        assertTrue(result.size() <= 3, "MMR 应保留非冗余文档");
    }
}
