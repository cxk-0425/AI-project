package com.kb.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PGVector 向量存储配置
 * <p>
 * Spring AI 的 pgvector auto-configure 默认已注册 VectorStore Bean，
 * 此处显式配置以便于自定义参数覆盖（如 HNSW 参数调优）。
 * 若 auto-configure 满足需求，可移除此类，仅保留 application.yml 配置。
 */
@Configuration
public class VectorStoreConfig {

    @Value("${spring.ai.vectorstore.pgvector.dimensions:1536}")
    private int dimensions;

    /**
     * 显式声明 VectorStore，允许在测试中 Mock 替换。
     * 生产中由 spring-ai-pgvector-store-spring-boot-starter 自动装配。
     */
    // 注释掉手动声明，使用 auto-configure
    // @Bean
    // public VectorStore vectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
    //     return PgVectorStore.builder(jdbcTemplate, embeddingModel)
    //             .dimensions(dimensions)
    //             .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
    //             .indexType(PgVectorStore.PgIndexType.HNSW)
    //             .initializeSchema(true)
    //             .build();
    // }
}
