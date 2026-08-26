package com.ecomagent.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bm25Index 关键词检索单测（§9.5 Stage1）。
 */
class Bm25IndexTest {

    private Document doc(String docId, int chunk, String text) {
        return new Document(text, Map.of(
                "doc_id", docId,
                "chunk_index", chunk));
    }

    @Test
    void keywordSearchHitsExactTerm() {
        Bm25Index index = new Bm25Index();
        index.index(doc("d1", 0, "七天无理由退货政策说明"));
        index.index(doc("d2", 0, "订单 SKU-1001 物流查询规则"));
        index.index(doc("d3", 0, "优惠券使用说明"));

        List<Document> hits = index.search("SKU-1001", 5);

        assertFalse(hits.isEmpty(), "关键词应命中至少一条");
        assertTrue(hits.get(0).getText().contains("SKU-1001"), "命中的首选应为包含关键词的文档");
    }

    @Test
    void emptyCorpusReturnsEmpty() {
        Bm25Index index = new Bm25Index();
        assertTrue(index.search("任意词", 5).isEmpty());
    }
}
