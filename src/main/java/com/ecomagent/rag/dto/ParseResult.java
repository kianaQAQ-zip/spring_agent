package com.ecomagent.rag.dto;

import java.util.Collections;
import java.util.List;

/**
 * 解析结果：归一化 doc-processor 与 Tika 两种来源的输出。
 *
 * <ul>
 *   <li>{@code reachable=true}：doc-processor 成功返回（mineru / pymupdf_fallback / text_fallback）。</li>
 *   <li>{@code reachable=false}：doc-processor 不可达，调用方应转 Tika 兜底。</li>
 * </ul>
 */
public class ParseResult {

    public final boolean reachable;
    public final String source;
    public final List<ParseBlock> blocks;
    public final double cleanScore;
    public final List<String> flags;
    public final String parsedText;

    private ParseResult(boolean reachable, String source, List<ParseBlock> blocks,
                        double cleanScore, List<String> flags, String parsedText) {
        this.reachable = reachable;
        this.source = source;
        this.blocks = blocks;
        this.cleanScore = cleanScore;
        this.flags = flags;
        this.parsedText = parsedText;
    }

    /** doc-processor 正常返回的解析结果 */
    public static ParseResult fromDocProcessor(List<ParseBlock> blocks, double cleanScore,
                                               List<String> flags, String parsedText) {
        return new ParseResult(true, "doc-processor", blocks, cleanScore, flags, parsedText);
    }

    /** Tika / 文本兜底解析结果 */
    public static ParseResult fallback(String source, List<ParseBlock> blocks, String parsedText) {
        return new ParseResult(false, source, blocks, 1.0, List.of(source), parsedText);
    }

    /** 自定义 cleanScore 的兜底结果（测试/特殊质量门禁场景） */
    public static ParseResult fallbackWithScore(String source, List<ParseBlock> blocks,
                                                double cleanScore, List<String> flags, String parsedText) {
        return new ParseResult(false, source, blocks, cleanScore, flags, parsedText);
    }

    /** doc-processor 不可达标记 */
    public static ParseResult unreachable() {
        return new ParseResult(false, "unreachable", Collections.emptyList(), 0.0,
                List.of("unreachable"), "");
    }
}
