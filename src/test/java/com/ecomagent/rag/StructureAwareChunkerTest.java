package com.ecomagent.rag;

import com.ecomagent.rag.dto.ParseBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StructureAwareChunker 纯逻辑单测（§9.4）：
 * 原子块保护、heading 上下文、弹性窗口 + 重叠。
 */
class StructureAwareChunkerTest {

    private final StructureAwareChunker chunker = new StructureAwareChunker();

    private ParseBlock block(String type, String text, int page) {
        return new ParseBlock(type, text, page, 0, TokenUtils.estimateTokens(text));
    }

    @Test
    void atomicTableIsItsOwnChunk() {
        List<ParseBlock> blocks = List.of(
                block("text", "普通段落内容一二三四五六七八九十", 1),
                block("table", "商品,价格,库存\n手机,3999,10\n耳机,399,50", 1),
                block("text", "另一段说明文字内容", 1));
        List<Chunk> chunks = chunker.chunk("doc1", "f.pdf", blocks);

        Chunk table = chunks.stream().filter(c -> c.blockType().equals("table")).findFirst().orElseThrow();
        assertTrue(table.atomic());
        // 原子块不被切分：table 行完整保留
        assertTrue(table.content().contains("手机,3999,10"));
        // 原子块独立成 chunk，不与其他 text 合并
        assertEquals("table", table.blockType());
    }

    @Test
    void longTextSplitsWithinTokenWindow() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            sb.append("这是一段用于测试分块的中文句子内容需要足够长才能触发切分逻辑。");
        }
        ParseBlock big = block("text", sb.toString(), 1);
        List<Chunk> chunks = chunker.chunk("doc2", "f.pdf", List.of(big));

        assertTrue(chunks.size() >= 2, "长文本应被切成多个 chunk");
        for (Chunk c : chunks) {
            assertFalse(c.atomic());
            assertTrue(c.tokenCount() <= 950,
                    "非原子 chunk token 应在弹性窗口内(含重叠)，实际=" + c.tokenCount());
        }
    }

    @Test
    void headingSetsContextPath() {
        List<ParseBlock> blocks = List.of(
                block("title", "退货政策", 1),
                block("text", "本政策适用于所有商品退货场景描述内容。", 1),
                block("section", "七天无理由", 1),
                block("text", "签收七日内可申请无理由退货相关说明文字。", 1));
        List<Chunk> chunks = chunker.chunk("doc3", "f.pdf", blocks);

        // 至少有一个 chunk 带上了 heading_path（节上下文）
        boolean hasHeading = chunks.stream().anyMatch(c -> c.headingPath() != null && !c.headingPath().isBlank());
        assertTrue(hasHeading);
        // 第二节标题生效：后续 chunk 的 heading_path 应包含"七天无理由"
        boolean secondHeadingPresent = chunks.stream()
                .anyMatch(c -> c.headingPath() != null && c.headingPath().contains("七天无理由"));
        assertTrue(secondHeadingPresent);
    }
}
