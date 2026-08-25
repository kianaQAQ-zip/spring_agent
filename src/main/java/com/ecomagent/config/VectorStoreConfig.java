package com.ecomagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PgVector 向量库配置（§0）。
 * - 维度 1024，与 text-embedding-v3 严格一致，否则建表即报错。
 * - 距离度量 COSINE。
 * - 关闭 starter 自动装配（application.yml 中 spring.ai.vectorstore.pgvector.enabled=false），
 *   由本 Bean 显式接管。
 * - schema 初始化交给我们自己的 db/init.sql（含 vector 扩展），故此处不调用 initializeSchema。
 */
@Configuration
public class VectorStoreConfig {

    @Bean
    public VectorStore vectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(1024)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .schemaName("public")
                .vectorTableName("vector_store")
                .initializeSchema(false)   // DDL 由 db/init.sql 接管，禁止 Spring 重复建表/建扩展
                .build();
    }
}
