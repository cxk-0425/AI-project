package com.kb.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 双 VectorStore 配置
 * <p>
 * 为两套 RAG 场景分别维护独立的 PGVector 表：
 * <ul>
 *   <li>docVectorStore  - 电商营销内部文档库（原 vector_store 表，@Primary）</li>
 *   <li>sopVectorStore  - 营销 QA + 活动 SOP 库（新建 sop_vector_store 表）</li>
 * </ul>
 *
 * 注意：启用手动 Bean 后，需要在 application.yml 中关闭 pgvector auto-configure：
 * spring.ai.vectorstore.pgvector.enabled=false（或保持 auto-configure 默认表，
 * 并在此 @Primary 中覆盖参数）。
 * 当前采用显式声明两个 Bean 的方案，完全可控。
 */
@Configuration
public class VectorStoreConfig {

    @Value("${spring.ai.vectorstore.pgvector.dimensions:1536}")
    private int dimensions;

    /**
     * 电商营销内部文档 VectorStore（主 VectorStore，兼容原有数据）
     * 对应表：vector_store
     */
    @Bean("docVectorStore")
    @Primary
    public VectorStore docVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .schemaName("public")
                .tableName("vector_store")
                .dimensions(dimensions)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(true)
                .removeExistingVectorStoreTable(false)
                .build();
    }

    /**
     * 营销 QA + 活动 SOP VectorStore（配置类问答专用）
     * 对应表：sop_vector_store
     */
    @Bean("sopVectorStore")
    public VectorStore sopVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .schemaName("public")
                .tableName("sop_vector_store")
                .dimensions(dimensions)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(true)
                .removeExistingVectorStoreTable(false)
                .build();
    }
}
