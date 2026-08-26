package com.ecomagent.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 混合召回 RRF 融合单测（§9.5 Stage1）。
 */
@ExtendWith(MockitoExtension.class)
class HybridRetrieverTest {

    @Mock
    private VectorStore vectorStore;
    @Mock
    private Bm25Index bm25Index;

    @Test
    void fusesAndDedupsByDocKey() {
        HybridRetriever retriever = new HybridRetriever(vectorStore, bm25Index);

        Document shared = new Document("内容A", Map.of("doc_id", "d1", "chunk_index", 0));
        Document vectorOnly = new Document("内容B", Map.of("doc_id", "d2", "chunk_index", 0));

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(shared, vectorOnly));
        when(bm25Index.search(anyString(), anyInt())).thenReturn(List.of(shared));

        List<Document> result = retriever.retrieve("查询", "default");

        // 向量与 BM25 都命中 shared，RRF 融合去重后应为 2 个唯一文档
        assertEquals(2, result.size());
    }
}
