package com.ecomagent.rag;

import com.ecomagent.rag.dto.ParseBlock;
import com.ecomagent.rag.dto.ParseResult;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Tika 兜底解析器（§9.2 轻量兜底）。
 *
 * <p>当 doc-processor 不可达时，用 Apache Tika 抽取纯文本（保留段落为单个 text 区块）；
 * 同时产出 {@code parsedText} 全文，供 {@code knowledge_doc.parsed_text} 落库（M5 点击看原文）。
 * 对扫描件 Tika 会丢内容 —— 这正是架构文档要求 doc-processor(MinerU+PaddleOCR) 真接解决的场景。
 */
@Component
public class TikaDocumentLoader {

    private final Tika tika = new Tika();

    public ParseResult parse(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            String text = tika.parseToString(in);
            ParseBlock block = new ParseBlock("text", text, 1, 0, TokenUtils.estimateTokens(text));
            return ParseResult.fallback("tika_fallback", List.of(block), text);
        } catch (IOException | TikaException e) {
            throw new IllegalStateException("Tika 解析失败: " + e.getMessage(), e);
        }
    }
}
