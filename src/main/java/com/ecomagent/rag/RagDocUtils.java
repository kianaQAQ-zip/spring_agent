package com.ecomagent.rag;

import org.springframework.ai.document.Document;

/**
 * RAG 文档元数据键与工具方法（M2 入库、M5 检索/溯源共用）。
 */
public final class RagDocUtils {

    public static final String KEY_DOC_ID = "doc_id";
    public static final String KEY_CHUNK_INDEX = "chunk_index";
    public static final String KEY_SOURCE = "source";
    public static final String KEY_PAGE = "page";
    public static final String KEY_BLOCK_TYPE = "block_type";
    public static final String KEY_ATOMIC = "atomic";
    public static final String KEY_TOKEN_COUNT = "token_count";
    public static final String KEY_HEADING_PATH = "heading_path";
    public static final String KEY_SCORE = "score";

    private RagDocUtils() {
    }

    /** 文档唯一键：doc_id#chunk_index，用于 BM25 与向量结果的 RRF 融合去重。 */
    public static String docKey(Document doc) {
        Object ci = doc.getMetadata().get(KEY_CHUNK_INDEX);
        return str(doc.getMetadata().get(KEY_DOC_ID)) + "#" + (ci == null ? "" : ci);
    }

    public static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
