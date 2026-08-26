package com.ecomagent.rag;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索流水线（§9.5 编排）：混合召回 → Cross-encoder 重排 → MMR 精排 → 编号。
 *
 * <p>替代 M3 的单一 {@code RetrievalAugmentationAdvisor}：手动编排以便拿到最终排名列表，
 * 从而先发 {@code citations} SSE 事件、后发文本流，并支撑 {@code [n]} 后校验。
 */
@Component
public class RetrievalPipeline {

    private static final int RERANK_TOP_N = 8;
    private static final int MAX_CONTEXT_TOKENS = 1500;

    private final HybridRetriever hybridRetriever;
    private final RerankService rerankService;
    private final MmrSelector mmrSelector;

    public RetrievalPipeline(HybridRetriever hybridRetriever,
                             RerankService rerankService,
                             MmrSelector mmrSelector) {
        this.hybridRetriever = hybridRetriever;
        this.rerankService = rerankService;
        this.mmrSelector = mmrSelector;
    }

    public RetrievalResult retrieve(String query, String tenant) {
        List<Document> candidates = hybridRetriever.retrieve(query, tenant);
        List<ScoredDoc> reranked = rerankService.rerank(query, candidates, RERANK_TOP_N);
        List<ScoredDoc> finalDocs = mmrSelector.select(reranked, MAX_CONTEXT_TOKENS);

        List<Document> documents = new ArrayList<>(finalDocs.size());
        List<Citation> citations = new ArrayList<>(finalDocs.size());
        for (int i = 0; i < finalDocs.size(); i++) {
            ScoredDoc sd = finalDocs.get(i);
            Document d = sd.doc();
            documents.add(d);
            citations.add(new Citation(
                    i + 1,
                    RagDocUtils.str(d.getMetadata().get(RagDocUtils.KEY_DOC_ID)),
                    RagDocUtils.str(d.getMetadata().get(RagDocUtils.KEY_SOURCE)),
                    d.getText(),
                    pageOf(d),
                    sd.score()));
        }
        return new RetrievalResult(documents, citations);
    }

    private Integer pageOf(Document d) {
        String raw = RagDocUtils.str(d.getMetadata().get(RagDocUtils.KEY_PAGE));
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
