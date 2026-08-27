package com.ecomagent.rag;

/**
 * 引用条目（§5 溯源）：回答中的 {@code [n]} 对应此列表，随 SSE {@code event: citations} 先发。
 * 携带 chunkIndex，供前端源抽屉调 {@code GET /kb/doc/{docId}?chunk={chunkIndex}} 取原文并高亮。
 */
public record Citation(
        int index,
        Integer chunkIndex,
        String docId,
        String source,
        String chunkContent,
        Integer page,
        double score) {
}
