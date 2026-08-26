package com.ecomagent.rag;

import java.util.List;

/**
 * 入库结果。
 * status: INGESTED（已入库） / QUARANTINED（clean_score 过低，隔离复核未入库）。
 */
public record IngestionResult(
        String docId,
        String source,
        String status,
        int chunkCount,
        double cleanScore,
        List<String> flags) {
}
