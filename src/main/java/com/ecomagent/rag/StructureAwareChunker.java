package com.ecomagent.rag;

import com.ecomagent.rag.dto.ParseBlock;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 结构感知柔性分块（§9.4）。
 *
 * <p>摒弃固定 500 token 刚性切分，改为结构感知 + 弹性窗口：
 * <ul>
 *   <li>语义边界优先：按区块累积，目标区间 400–800 token，溢出时在区块边界 flush。</li>
 *   <li>原子块保护：{@code table/figure/key_data} 整块不切，超长作为独立宽 chunk。</li>
 *   <li>层级：heading 作为 chunk 起点并维护 {@code heading_path}，chunk 自带节上下文。</li>
 *   <li>重叠：相邻 chunk 携带约 12% 尾部文本，保跨边界语义。</li>
 * </ul>
 */
@Service
public class StructureAwareChunker {

    private static final int MIN_TOKENS = 400;
    private static final int MAX_TOKENS = 800;
    private static final double OVERLAP_RATIO = 0.12;
    private static final int MAX_HEADING_PATH = 3;

    private static final Set<String> ATOMIC = Set.of("table", "figure", "key_data");
    private static final Set<String> HEADING = Set.of("title", "heading", "section");

    public List<Chunk> chunk(String docId, String source, List<ParseBlock> blocks) {
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int curTokens = 0;
        List<String> headingPath = new ArrayList<>();
        int lastPage = 1;
        int idx = 0;

        for (ParseBlock b : blocks) {
            String type = b.blockType() == null ? "text" : b.blockType().toLowerCase();
            if (b.page() != null) {
                lastPage = b.page();
            }
            String text = b.text() == null ? "" : b.text();

            if (ATOMIC.contains(type)) {
                idx = flush(chunks, docId, source, cur, curTokens, headingPath, lastPage, idx);
                int tok = b.tokenCount() != null ? b.tokenCount() : TokenUtils.estimateTokens(text);
                chunks.add(new Chunk(docId, idx++, source, text, type, true, lastPage,
                        tok, String.join(" / ", headingPath)));
                continue;
            }

            if (HEADING.contains(type)) {
                idx = flush(chunks, docId, source, cur, curTokens, headingPath, lastPage, idx);
                pushHeading(headingPath, text);
                cur = new StringBuilder(text);
                curTokens = TokenUtils.estimateTokens(text);
                continue;
            }

            // text / paragraph / list / footnote：累积至 ~600 token 后在边界 flush
            int tok = b.tokenCount() != null ? b.tokenCount() : TokenUtils.estimateTokens(text);
            if (curTokens + tok > MAX_TOKENS && curTokens >= MIN_TOKENS) {
                idx = flush(chunks, docId, source, cur, curTokens, headingPath, lastPage, idx);
            }
            if (cur.length() > 0) {
                cur.append("\n");
            }
            cur.append(text);
            curTokens += tok;
        }
        flush(chunks, docId, source, cur, curTokens, headingPath, lastPage, idx);
        return chunks;
    }

    /** 提交当前累积缓冲为一个 chunk；返回下一个 chunk_index，并保留尾部重叠文本 */
    private int flush(List<Chunk> chunks, String docId, String source, StringBuilder cur,
                      int curTokens, List<String> headingPath, int page, int idx) {
        String text = cur.toString().strip();
        if (text.isEmpty()) {
            cur.setLength(0);
            return idx;
        }
        int tok = curTokens > 0 ? curTokens : TokenUtils.estimateTokens(text);
        chunks.add(new Chunk(docId, idx, source, text, "text", false, page, tok,
                String.join(" / ", headingPath)));
        // 重叠：保留约 12% 尾部作为下一 chunk 前缀
        String overlap = tailByTokens(text, (int) (tok * OVERLAP_RATIO));
        cur.setLength(0);
        cur.append(overlap);
        return idx + 1;
    }

    private void pushHeading(List<String> headingPath, String heading) {
        if (heading == null || heading.isBlank()) {
            return;
        }
        headingPath.add(heading.strip());
        while (headingPath.size() > MAX_HEADING_PATH) {
            headingPath.remove(0);
        }
    }

    /** 近似取文本尾部约 n 个 token 对应的子串（CJK 约 1.5 字符/token） */
    private String tailByTokens(String text, int n) {
        if (n <= 0) {
            return "";
        }
        int chars = (int) (n * 1.5);
        if (chars >= text.length()) {
            return text;
        }
        return text.substring(text.length() - chars);
    }
}
