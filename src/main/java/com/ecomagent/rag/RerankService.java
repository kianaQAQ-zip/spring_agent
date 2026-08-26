package com.ecomagent.rag;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 重排服务（§9.5 Stage2）：Cross-encoder 重排经 doc-processor 真接，
 * 不可达时降级为「score 阈值 + 排序伪分」（后续 MMR 精排）。
 */
@Component
public class RerankService {

    private final DocProcessorClient docProcessorClient;

    public RerankService(DocProcessorClient docProcessorClient) {
        this.docProcessorClient = docProcessorClient;
    }

    /**
     * @return 按相关分数降序的候选（前 topN）。doc-processor 不可达时以召回顺序赋伪分。
     */
    public List<ScoredDoc> rerank(String query, List<Document> candidates, int topN) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<String> ids = candidates.stream().map(RagDocUtils::docKey).toList();
        List<String> texts = candidates.stream().map(Document::getText).toList();
        List<DocProcessorClient.RerankHit> hits = docProcessorClient.rerank(query, ids, texts, topN);

        if (!hits.isEmpty()) {
            Map<String, DocProcessorClient.RerankHit> byId = hits.stream()
                    .collect(Collectors.toMap(DocProcessorClient.RerankHit::id, h -> h, (a, b) -> a));
            List<ScoredDoc> out = new ArrayList<>();
            for (DocProcessorClient.RerankHit h : hits) {
                Document doc = byKey(candidates, h.id());
                if (doc != null) {
                    out.add(new ScoredDoc(doc, h.score()));
                }
            }
            return out;
        }

        // 降级：召回顺序赋伪分（越靠前分越高）
        List<ScoredDoc> out = new ArrayList<>();
        int n = Math.min(topN, candidates.size());
        for (int i = 0; i < n; i++) {
            out.add(new ScoredDoc(candidates.get(i), 1.0 / (i + 1)));
        }
        return out;
    }

    private Document byKey(List<Document> candidates, String key) {
        for (Document d : candidates) {
            if (Objects.equals(RagDocUtils.docKey(d), key)) {
                return d;
            }
        }
        return null;
    }
}
