package com.ecomagent.rag;

/**
 * 知识文档元信息（对应 {@code knowledge_doc} 表，§7 / M2）。
 * {@code parsedText} 存解析全文，供 M5 点击引用溯源时展示原文。
 *
 * <p>M5 运营扩展：{@code kbId} 所属知识库、{@code fileSize} 原文件字节、
 * {@code status} 处理状态（INGESTED / QUARANTINED）。
 */
public record KnowledgeDoc(
        String id,
        String docId,
        String tenantId,
        String source,
        int chunkCount,
        String parsedText,
        String kbId,
        Long fileSize,
        String status,
        Double cleanScore) {

    public static final String STATUS_INGESTED = "INGESTED";
    public static final String STATUS_QUARANTINED = "QUARANTINED";
}
