package com.ecomagent.rag;

import com.ecomagent.common.TenantContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 知识库管理服务（M5 运营）：文档列表/详情/删除、多知识库、统计总览、检索测试。
 *
 * <p>查询口径：
 * <ul>
 *   <li>文档列表 = {@code knowledge_doc}（分页 + 知识库/状态/名称筛选）；</li>
 *   <li>chunk 预览 = 直接查 {@code vector_store.metadata->>'doc_id'}——向量库是 chunk 的权威数据源；</li>
 *   <li>统计 = knowledge_doc 聚合 + vector_store count。</li>
 * </ul>
 */
@Service
public class KbManageService {

    private final JdbcTemplate jdbcTemplate;
    private final VectorStore vectorStore;
    private final KnowledgeDocRepository knowledgeDocRepository;

    public KbManageService(JdbcTemplate jdbcTemplate, VectorStore vectorStore,
                           KnowledgeDocRepository knowledgeDocRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.vectorStore = vectorStore;
        this.knowledgeDocRepository = knowledgeDocRepository;
    }

    // ---------- 文档列表 / 详情 / 删除 ----------

    /** 文档分页列表：kbId/status/keyword 筛选 + 排序（created_at|chunk_count|source）。 */
    public Map<String, Object> listDocuments(String kbId, String status, String keyword,
                                             String sort, String order, int page, int size) {
        String tenantId = TenantContext.get();
        StringBuilder where = new StringBuilder(" WHERE tenant_id = ? ");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (kbId != null && !kbId.isBlank()) {
            where.append(" AND kb_id = ? ");
            args.add(kbId);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND status = ? ");
            args.add(status);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND source ILIKE ? ");
            args.add("%" + keyword + "%");
        }
        String sortCol = switch (sort == null ? "" : sort) {
            case "chunkCount" -> "chunk_count";
            case "source" -> "source";
            default -> "created_at";
        };
        String sortOrder = "asc".equalsIgnoreCase(order) ? "ASC" : "DESC";

        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM knowledge_doc" + where, Long.class, args.toArray());

        String sql = "SELECT doc_id, source, kb_id, chunk_count, file_size, status, clean_score, created_at "
                + "FROM knowledge_doc" + where
                + " ORDER BY " + sortCol + " " + sortOrder + " LIMIT ? OFFSET ?";
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(Math.min(Math.max(size, 1), 100));
        pageArgs.add((Math.max(page, 1) - 1) * (long) Math.min(Math.max(size, 1), 100));

        List<Map<String, Object>> items = jdbcTemplate.queryForList(sql, pageArgs.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("page", Math.max(page, 1));
        result.put("size", Math.min(Math.max(size, 1), 100));
        result.put("items", items);
        return result;
    }

    /** 文档详情：元信息 + chunk 列表（从 vector_store 按 doc_id 取，权威数据源）。 */
    public Map<String, Object> docDetail(String docId) {
        String tenantId = TenantContext.get();
        List<Map<String, Object>> docs = jdbcTemplate.queryForList(
                "SELECT doc_id, source, kb_id, chunk_count, file_size, status, clean_score, created_at, "
                        + "length(parsed_text) AS text_length "
                        + "FROM knowledge_doc WHERE tenant_id = ? AND doc_id = ?",
                tenantId, docId);
        if (docs.isEmpty()) {
            return null;
        }
        Map<String, Object> detail = new LinkedHashMap<>(docs.get(0));

        List<Map<String, Object>> chunks = jdbcTemplate.queryForList("""
                        SELECT id, content,
                               metadata->>'chunk_index'  AS chunk_index,
                               metadata->>'block_type'   AS block_type,
                               metadata->>'heading_path' AS heading_path,
                               metadata->>'token_count'  AS token_count,
                               metadata->>'page'         AS page
                        FROM vector_store
                        WHERE metadata->>'doc_id' = ?
                        ORDER BY (metadata->>'chunk_index')::int
                        """, docId);
        detail.put("chunks", chunks);
        detail.put("actualChunkCount", chunks.size());
        return detail;
    }

    /** 删除文档：向量 + 元信息一起删（幂等，重复删不报错）。 */
    public boolean deleteDocument(String docId) {
        KnowledgeDoc doc = knowledgeDocRepository.findByDocId(docId);
        if (doc == null) {
            return false;
        }
        try {
            vectorStore.delete(new FilterExpressionBuilder().eq("doc_id", docId).build());
        } catch (Exception e) {
            // 向量删除失败不阻断元信息删除——避免留下"幽灵文档"
            org.slf4j.LoggerFactory.getLogger(KbManageService.class)
                    .warn("删除向量失败（元信息仍删除）: docId={} err={}", docId, e.getMessage());
        }
        knowledgeDocRepository.deleteByDocId(docId);
        return true;
    }

    // ---------- 多知识库 ----------

    public List<Map<String, Object>> listKbs() {
        String tenantId = TenantContext.get();
        return jdbcTemplate.queryForList("""
                        SELECT k.kb_id, k.name, k.description, k.embedding_model, k.created_at,
                               count(d.doc_id)                                      AS doc_count,
                               coalesce(sum(d.chunk_count), 0)                      AS total_chunks,
                               count(*) FILTER (WHERE d.status = 'QUARANTINED')     AS quarantined
                        FROM knowledge_base k
                        LEFT JOIN knowledge_doc d
                          ON d.tenant_id = k.tenant_id AND d.kb_id = k.kb_id
                        WHERE k.tenant_id = ?
                        GROUP BY k.kb_id, k.name, k.description, k.embedding_model, k.created_at
                        ORDER BY k.created_at ASC
                        """, tenantId);
    }

    public Map<String, Object> createKb(String name, String description) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("知识库名称不能为空");
        }
        String tenantId = TenantContext.get();
        String kbId = "kb-" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO knowledge_base (id, kb_id, tenant_id, name, description) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), kbId, tenantId, name.trim(),
                description == null ? "" : description.trim());
        Map<String, Object> kb = new LinkedHashMap<>();
        kb.put("kbId", kbId);
        kb.put("name", name.trim());
        kb.put("description", description == null ? "" : description.trim());
        return kb;
    }

    /** 删除知识库（含其中全部文档）。默认知识库不允许删除。 */
    public boolean deleteKb(String kbId) {
        if ("default".equals(kbId)) {
            throw new IllegalArgumentException("默认知识库不允许删除");
        }
        String tenantId = TenantContext.get();
        Integer used = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM knowledge_base WHERE tenant_id = ? AND kb_id = ?",
                Integer.class, tenantId, kbId);
        if (used == null || used == 0) {
            return false;
        }
        // 级联删除该知识库下所有文档（向量 + 元信息）
        List<String> docIds = jdbcTemplate.queryForList(
                "SELECT doc_id FROM knowledge_doc WHERE tenant_id = ? AND kb_id = ?",
                String.class, tenantId, kbId);
        for (String docId : docIds) {
            deleteDocument(docId);
        }
        jdbcTemplate.update("DELETE FROM knowledge_base WHERE tenant_id = ? AND kb_id = ?",
                tenantId, kbId);
        return true;
    }

    // ---------- 统计总览 ----------

    public Map<String, Object> stats() {
        String tenantId = TenantContext.get();
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                        SELECT count(*)                                          AS total_docs,
                               coalesce(sum(chunk_count), 0)                     AS total_chunks,
                               count(*) FILTER (WHERE status = 'QUARANTINED')    AS quarantined,
                               count(*) FILTER (WHERE created_at >= date_trunc('month', now())) AS month_new
                        FROM knowledge_doc WHERE tenant_id = ?
                        """, tenantId);
        m.putAll(rows.isEmpty() ? Map.of() : rows.get(0));
        Long vectorRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM vector_store", Long.class);
        m.put("vector_rows", vectorRows);
        m.put("kb_count", jdbcTemplate.queryForObject(
                "SELECT count(*) FROM knowledge_base WHERE tenant_id = ?", Integer.class, tenantId));
        return m;
    }

    // ---------- 检索测试 ----------

    /** 知识库检索测试：直接向量召回，返回命中 chunk 预览（不走 LLM，零成本验证覆盖）。 */
    public List<Map<String, Object>> retrievalTest(String kbId, String query, int topK) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        Filter.Expression expr = (kbId == null || kbId.isBlank())
                ? null
                : b.eq("kb_id", kbId).build();
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(Math.min(Math.max(topK, 1), 20));
        if (expr != null) {
            builder.filterExpression(expr);
        }
        List<Document> docs = vectorStore.similaritySearch(builder.build());
        List<Map<String, Object>> hits = new ArrayList<>(docs.size());
        for (Document d : docs) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("source", d.getMetadata().get("source"));
            h.put("docId", d.getMetadata().get("doc_id"));
            h.put("chunkIndex", d.getMetadata().get("chunk_index"));
            h.put("headingPath", d.getMetadata().get("heading_path"));
            String text = d.getText() == null ? "" : d.getText();
            h.put("snippet", text.length() <= 200 ? text : text.substring(0, 200) + "...");
            hits.add(h);
        }
        return hits;
    }
}
