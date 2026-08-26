package com.ecomagent.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合召回（§9.5 Stage1）：向量宽召回 + BM25 关键词召回，经 RRF 融合去重。
 *
 * <p>向量召回 topK=20（宽召回，为后续重排留候选），BM25 命中 SKU/政策编码等精确串，
 * 二者按 RRF（k=60）融合，兼顾语义相关与精确匹配。
 */
@Component
public class HybridRetriever {

    private static final int VECTOR_TOP_K = 20;
    private static final int BM25_TOP_K = 20;
    private static final double VECTOR_THRESHOLD = 0.6;
    private static final double RRF_K = 60.0;

    private final VectorStore vectorStore;
    private final Bm25Index bm25Index;

    public HybridRetriever(VectorStore vectorStore, Bm25Index bm25Index) {
        this.vectorStore = vectorStore;
        this.bm25Index = bm25Index;
    }

    public List<Document> retrieve(String query, String tenant) {
        Filter.Expression tenantFilter = new FilterExpressionBuilder()
                .eq("tenant_id", tenant).build();
        List<Document> vectorDocs = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(VECTOR_TOP_K)
                .similarityThreshold(VECTOR_THRESHOLD)
                .filterExpression(tenantFilter)
                .build());
        List<Document> bm25Docs = bm25Index.search(query, BM25_TOP_K);

        Map<String, Double> rrf = new LinkedHashMap<>();
        Map<String, Document> byKey = new LinkedHashMap<>();
        accumulate(rrf, byKey, vectorDocs);
        accumulate(rrf, byKey, bm25Docs);

        return rrf.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(e -> byKey.get(e.getKey()))
                .toList();
    }

    private void accumulate(Map<String, Double> rrf, Map<String, Document> byKey,
                            List<Document> docs) {
        for (int i = 0; i < docs.size(); i++) {
            Document d = docs.get(i);
            String key = RagDocUtils.docKey(d);
            byKey.putIfAbsent(key, d);
            rrf.merge(key, 1.0 / (RRF_K + i + 1), Double::sum);
        }
    }
}
