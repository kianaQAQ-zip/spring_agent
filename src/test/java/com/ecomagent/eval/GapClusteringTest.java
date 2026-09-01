package com.ecomagent.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 贪心聚类纯算法单测（M4）：余弦相似度 + 分组决策。
 */
class GapClusteringTest {

    @Test
    void cosineIdenticalVectorsIsOne() {
        float[] a = {1, 2, 3};
        assertEquals(1.0, GapClustering.cosine(a, a), 1e-6);
    }

    @Test
    void cosineOrthogonalVectorsIsZero() {
        float[] a = {1, 0, 0};
        float[] b = {0, 1, 0};
        assertEquals(0.0, GapClustering.cosine(a, b), 1e-6);
    }

    @Test
    void cosineZeroVectorIsZero() {
        float[] a = {0, 0, 0};
        float[] b = {1, 0, 0};
        assertEquals(0.0, GapClustering.cosine(a, b), 1e-6);
    }

    @Test
    void similarQueriesClusterTogether() {
        // "退货"和"退款"语义相近 → 向量相近 → 归入同簇
        List<String> queries = List.of("7天无理由退货", "过了7天还能退吗", "怎么退款");
        List<float[]> vecs = List.of(
                new float[]{1.0f, 0.0f, 0.0f},
                new float[]{0.95f, 0.1f, 0.0f},
                new float[]{0.9f, 0.2f, 0.0f});

        List<GapClustering.Cluster> clusters = GapClustering.cluster(queries, vecs, 0.8);

        assertEquals(1, clusters.size(), "语义相近应聚成一簇");
        assertEquals(3, clusters.get(0).memberCount());
    }

    @Test
    void dissimilarQueriesSplitApart() {
        List<String> queries = List.of("退货政策", "运费多少钱", "优惠券怎么用");
        List<float[]> vecs = List.of(
                new float[]{1.0f, 0.0f, 0.0f},
                new float[]{0.0f, 1.0f, 0.0f},
                new float[]{0.0f, 0.0f, 1.0f});

        List<GapClustering.Cluster> clusters = GapClustering.cluster(queries, vecs, 0.8);

        assertEquals(3, clusters.size(), "语义不同应分成多簇");
        assertTrue(clusters.stream().allMatch(c -> c.memberCount() == 1));
    }
}
