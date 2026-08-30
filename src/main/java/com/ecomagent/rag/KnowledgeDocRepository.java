package com.ecomagent.rag;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * knowledge_doc 表访问（JDBC，无 JPA 依赖）。
 * 记录文档来源、切块数、解析全文，供 M5 溯源与 M2 入库落盘。
 */
@Repository
public class KnowledgeDocRepository {

    private static final RowMapper<KnowledgeDoc> ROW_MAPPER = KnowledgeDocRepository::mapRow;

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeDocRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(KnowledgeDoc d) {
        // created_at 由 DEFAULT now() 自动填充：PG JDBC 42.7.7 的 setObject() 无法推断 Instant 的 SQL 类型，
        // 显式传参会报 "Can't infer the SQL type to use for an instance of java.time.Instant"。
        jdbcTemplate.update(
                "INSERT INTO knowledge_doc (id, doc_id, tenant_id, source, chunk_count, parsed_text) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                d.id(), d.docId(), d.tenantId(), d.source(), d.chunkCount(), d.parsedText());
    }

    public KnowledgeDoc findByDocId(String docId) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_doc WHERE doc_id = ?", Integer.class, docId);
        if (cnt == null || cnt == 0) {
            return null;
        }
        return jdbcTemplate.queryForObject(
                "SELECT id, doc_id, tenant_id, source, chunk_count, parsed_text "
                        + "FROM knowledge_doc WHERE doc_id = ?", ROW_MAPPER, docId);
    }

    private static KnowledgeDoc mapRow(ResultSet rs, int rn) throws SQLException {
        return new KnowledgeDoc(
                rs.getString("id"),
                rs.getString("doc_id"),
                rs.getString("tenant_id"),
                rs.getString("source"),
                rs.getInt("chunk_count"),
                rs.getString("parsed_text"));
    }
}
