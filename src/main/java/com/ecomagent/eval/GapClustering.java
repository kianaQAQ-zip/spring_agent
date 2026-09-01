package com.ecomagent.eval;

import java.util.ArrayList;
import java.util.List;

/**
 * 贪心聚类纯算法（M4，无 IO，便于单测）。
 *
 * <p>把"字面不同、语义相同"的提问按 embedding 余弦相似度聚成一类。
 * 贪心策略：每个向量与已有簇的质心（首个成员向量）比较，相似度 &ge; 阈值则归入，否则新建簇。
 *
 * <p>不依赖 Spring / DB / LLM——嵌入由调用方 {@code GapClusterService} 传入，
 * 本类只做分组决策。
 */
public final class GapClustering {

    private GapClustering() {
    }

    public record Cluster(String representative, float[] centroid, int memberCount) {
    }

    /** 贪心聚类，返回簇列表（成员数已累计）。 */
    public static List<Cluster> cluster(List<String> queries, List<float[]> vectors, double threshold) {
        List<Cluster> clusters = new ArrayList<>();
        for (int i = 0; i < queries.size(); i++) {
            float[] v = vectors.get(i);
            Cluster best = null;
            double bestSim = -1;
            for (Cluster c : clusters) {
                double sim = cosine(v, c.centroid());
                if (sim > bestSim) {
                    bestSim = sim;
                    best = c;
                }
            }
            if (best != null && bestSim >= threshold) {
                clusters.set(clusters.indexOf(best),
                        new Cluster(best.representative(), best.centroid(), best.memberCount() + 1));
            } else {
                clusters.add(new Cluster(queries.get(i), v, 1));
            }
        }
        return clusters;
    }

    /** 余弦相似度，范围 [-1, 1]。零向量返回 0。 */
    public static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("维度不一致: " + a.length + " vs " + b.length);
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
