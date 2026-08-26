package com.ecomagent.rag;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 过程内 BM25 检索索引（§9.5 Stage1 关键词精确串，无需 Lucene/Docker）。
 *
 * <p>入库时由 {@code KbIngestionService} 调 {@link #index(Document)} 建索引；
 * 检索时 {@link #search(String, int)} 返回按 BM25 分数降序的关键词命中文档。
 * 与向量召回互补：能命中「SKU / 政策编码」等精确串，而这些串在语义向量里常被稀释。
 */
@Component
public class Bm25Index {

    private static final double K1 = 1.5;
    private static final double B = 0.75;
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final Map<String, Document> docsByKey = new LinkedHashMap<>();
    private final Map<String, Map<String, Integer>> termFreq = new HashMap<>();
    private final Map<String, Integer> docFreq = new HashMap<>();
    private final Map<String, Integer> docLength = new HashMap<>();
    private int totalLength = 0;

    public synchronized void index(Document doc) {
        String key = RagDocUtils.docKey(doc);
        List<String> tokens = tokenize(doc.getText());
        docsByKey.put(key, doc);
        docLength.put(key, tokens.size());
        totalLength += tokens.size();
        Map<String, Integer> tf = new HashMap<>();
        for (String t : tokens) {
            tf.merge(t, 1, Integer::sum);
        }
        termFreq.put(key, tf);
        for (String t : tf.keySet()) {
            docFreq.merge(t, 1, Integer::sum);
        }
    }

    public synchronized void clear() {
        docsByKey.clear();
        termFreq.clear();
        docFreq.clear();
        docLength.clear();
        totalLength = 0;
    }

    public synchronized int size() {
        return docsByKey.size();
    }

    public synchronized List<Document> search(String query, int topK) {
        List<String> queryTokens = tokenize(query);
        double avgdl = docsByKey.isEmpty() ? 1.0 : (double) totalLength / docsByKey.size();
        Map<String, Double> scores = new HashMap<>();
        for (String key : docsByKey.keySet()) {
            double s = bm25Score(key, queryTokens, avgdl);
            if (s > 0) {
                scores.put(key, s);
            }
        }
        return scores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(Math.max(0, topK))
                .map(e -> docsByKey.get(e.getKey()))
                .toList();
    }

    private double bm25Score(String key, List<String> queryTokens, double avgdl) {
        Map<String, Integer> tf = termFreq.get(key);
        if (tf == null || tf.isEmpty()) {
            return 0;
        }
        int len = docLength.getOrDefault(key, 0);
        double score = 0;
        for (String t : queryTokens) {
            int f = tf.getOrDefault(t, 0);
            if (f == 0) {
                continue;
            }
            int df = docFreq.getOrDefault(t, 0);
            int n = docsByKey.size();
            double idf = Math.log((n - df + 0.5) / (df + 0.5) + 1);
            double denom = f + K1 * (1 - B + B * len / avgdl);
            score += idf * f * (K1 + 1) / denom;
        }
        return score;
    }

    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.toLowerCase();
        List<String> tokens = new ArrayList<>();
        for (String part : TOKEN_SPLIT.split(normalized)) {
            if (part.isEmpty()) {
                continue;
            }
            if (part.length() == 1) {
                tokens.add(part);
            } else if (isCjk(part.charAt(0))) {
                // CJK 段：按字符 bigram 切分，兼顾子串匹配
                for (int i = 0; i < part.length() - 1; i++) {
                    tokens.add(part.substring(i, i + 2));
                }
                tokens.add(part.substring(part.length() - 1));
            } else {
                tokens.add(part);
            }
        }
        return tokens;
    }

    private boolean isCjk(char c) {
        return (c >= 0x4e00 && c <= 0x9fff);
    }
}
