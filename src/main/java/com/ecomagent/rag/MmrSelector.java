package com.ecomagent.rag;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MMR 精排 + metadata 加权 + token 预算（§9.5 Stage3）。
 *
 * <p>在重排结果之上做三件事：
 * <ol>
 *   <li><b>metadata 加权</b>：{@code block_type=table} / {@code atomic=true} 的结构化块提权；</li>
 *   <li><b>MMR 去冗余</b>：以 token 集合 Jaccard 相似度作为文档间冗余信号，在「相关且不多样」中权衡；</li>
 *   <li><b>token 预算</b>：累加至约 {@code maxTokens} 停止，防止上下文爆炸。</li>
 * </ol>
 */
@Component
public class MmrSelector {

    private static final double LAMBDA = 0.7;
    private static final double TABLE_BOOST = 0.2;

    public List<ScoredDoc> select(List<ScoredDoc> candidates, int maxTokens) {
        List<ScoredDoc> boosted = new ArrayList<>(candidates.size());
        for (ScoredDoc sd : candidates) {
            boosted.add(new ScoredDoc(sd.doc(), sd.score() + boost(sd.doc())));
        }
        List<ScoredDoc> result = new ArrayList<>();
        List<ScoredDoc> remaining = new ArrayList<>(boosted);
        int budget = 0;
        while (!remaining.isEmpty() && budget < maxTokens) {
            ScoredDoc best = pickBest(remaining, result);
            remaining.remove(best);
            result.add(best);
            budget += tokenCount(best.doc());
        }
        return result;
    }

    private ScoredDoc pickBest(List<ScoredDoc> remaining, List<ScoredDoc> selected) {
        ScoredDoc best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (ScoredDoc cand : remaining) {
            double redundancy = selected.isEmpty() ? 0 : maxJaccard(cand.doc(), selected);
            double mmr = LAMBDA * cand.score() - (1 - LAMBDA) * redundancy;
            if (mmr > bestScore) {
                bestScore = mmr;
                best = cand;
            }
        }
        return best;
    }

    private double boost(Document doc) {
        String blockType = RagDocUtils.str(doc.getMetadata().get(RagDocUtils.KEY_BLOCK_TYPE));
        boolean atomic = Boolean.parseBoolean(
                RagDocUtils.str(doc.getMetadata().get(RagDocUtils.KEY_ATOMIC)));
        return ("table".equalsIgnoreCase(blockType) || atomic) ? TABLE_BOOST : 0;
    }

    private double maxJaccard(Document cand, List<ScoredDoc> selected) {
        Set<String> a = tokenSet(cand.getText());
        double max = 0;
        for (ScoredDoc s : selected) {
            Set<String> b = tokenSet(s.doc().getText());
            double j = jaccard(a, b);
            if (j > max) {
                max = j;
            }
        }
        return max;
    }

    private double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        return (double) inter.size() / union.size();
    }

    private Set<String> tokenSet(String text) {
        Set<String> set = new HashSet<>();
        if (text == null) {
            return set;
        }
        for (String part : text.toLowerCase().split("[^\\p{L}\\p{N}]+")) {
            if (part.length() >= 2) {
                set.add(part);
            }
        }
        return set;
    }

    private int tokenCount(Document doc) {
        String raw = RagDocUtils.str(doc.getMetadata().get(RagDocUtils.KEY_TOKEN_COUNT));
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return TokenUtils.estimateTokens(doc.getText());
        }
    }
}
