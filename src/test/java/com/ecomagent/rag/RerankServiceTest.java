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
 * 重排服务单测（§9.5 Stage2）：Cross-encoder 命中 + 不可达降级。
 */
@ExtendWith(MockitoExtension.class)
class RerankServiceTest {

    @Mock
    private DocProcessorClient docProcessorClient;

    private Document doc(String docId, int chunk, String text) {
        return new Document(text, Map.of("doc_id", docId, "chunk_index", chunk));
    }

    @Test
    void usesCrossEncoderRanking() {
        RerankService service = new RerankService(docProcessorClient);
        Document d1 = doc("d1", 0, "内容一");
        Document d2 = doc("d2", 0, "内容二");
        when(docProcessorClient.rerank(anyString(), anyList(), anyList(), anyInt()))
                .thenReturn(List.of(
                        new DocProcessorClient.RerankHit("d2#0", 0.9),
                        new DocProcessorClient.RerankHit("d1#0", 0.5)));

        List<ScoredDoc> result = service.rerank("q", List.of(d1, d2), 8);

        assertEquals(2, result.size());
        assertEquals("d2#0", RagDocUtils.docKey(result.get(0).doc()), "应按重排分数降序");
    }

    @Test
    void fallsBackWhenUnreachable() {
        RerankService service = new RerankService(docProcessorClient);
        Document d1 = doc("d1", 0, "内容一");
        when(docProcessorClient.rerank(anyString(), anyList(), anyList(), anyInt()))
                .thenReturn(List.of());

        List<ScoredDoc> result = service.rerank("q", List.of(d1), 8);

        assertEquals(1, result.size(), "不可达时应降级返回候选");
    }
}
