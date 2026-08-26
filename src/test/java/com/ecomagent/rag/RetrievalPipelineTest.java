package com.ecomagent.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 检索流水线编排单测（§9.5）：混合召回 → 重排 → MMR → 编号。
 */
@ExtendWith(MockitoExtension.class)
class RetrievalPipelineTest {

    @Mock
    private HybridRetriever hybridRetriever;
    @Mock
    private RerankService rerankService;
    @Mock
    private MmrSelector mmrSelector;

    @Test
    void numbersCitationsFromOne() {
        RetrievalPipeline pipeline = new RetrievalPipeline(hybridRetriever, rerankService, mmrSelector);
        Document d1 = new Document("内容一", Map.of("doc_id", "d1", "chunk_index", 0, "source", "a.pdf", "page", 1));
        Document d2 = new Document("内容二", Map.of("doc_id", "d2", "chunk_index", 0, "source", "b.pdf", "page", 2));

        when(hybridRetriever.retrieve(anyString(), anyString())).thenReturn(List.of(d1, d2));
        when(rerankService.rerank(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(new ScoredDoc(d1, 0.9), new ScoredDoc(d2, 0.8)));
        when(mmrSelector.select(anyList(), anyInt()))
                .thenReturn(List.of(new ScoredDoc(d1, 0.9), new ScoredDoc(d2, 0.8)));

        RetrievalResult rr = pipeline.retrieve("q", "default");

        assertEquals(2, rr.citations().size());
        assertEquals(1, rr.citations().get(0).index(), "引用编号应从 1 开始");
        assertEquals("a.pdf", rr.citations().get(0).source());
        assertEquals(2, rr.citations().get(1).index());
        assertEquals("b.pdf", rr.citations().get(1).source());
    }
}
