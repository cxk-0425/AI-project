-- ============================================================
-- schema.sql - PGVector 知识库初始化脚本
-- 由 docker-compose initdb 自动执行
-- ============================================================

-- 1. 启用 pgvector 和中文分词扩展
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ============================================================
-- 2. 文档元数据表（独立管理，与 Spring AI vector_store 分离）
-- ============================================================
CREATE TABLE IF NOT EXISTS kb_document (
    id            UUID DEFAULT uuid_generate_v4() PRIMARY KEY,
    filename      VARCHAR(512) NOT NULL,
    file_hash     VARCHAR(64)  NOT NULL UNIQUE,   -- SHA-256，用于去重
    file_size     BIGINT       NOT NULL,
    file_type     VARCHAR(32)  NOT NULL,          -- pdf / docx / txt / md / url / rpc
    chunk_count   INTEGER      DEFAULT 0,
    status        VARCHAR(32)  DEFAULT 'PENDING', -- PENDING / PROCESSING / DONE / ERROR
    error_msg     TEXT,
    source_type   VARCHAR(16)  DEFAULT 'FILE',    -- 录入来源：FILE / URL / RPC
    source_url    TEXT,                           -- URL 录入时记录原始地址
    source_system VARCHAR(128),                   -- RPC 录入时记录来源系统标识
    created_at    TIMESTAMP    DEFAULT NOW(),
    updated_at    TIMESTAMP    DEFAULT NOW()
);

-- 为已有表添加新列（幂等执行）
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS source_type   VARCHAR(16)  DEFAULT 'FILE';
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS source_url    TEXT;
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS source_system VARCHAR(128);

-- ============================================================
-- 3. Spring AI 会自动创建 vector_store 表（initialize-schema: true）
-- 此处添加全文检索优化支持
-- ============================================================

-- 注意：vector_store 表由 Spring AI 在首次运行时自动创建
-- 以下索引和函数在表创建后由系统自动应用

-- 4. 全文检索支持函数（由 HybridSearchService 在运行时调用）
-- 使用 PostgreSQL 内置的 tsvector 和 tsquery 实现 BM25

-- 5. 全文检索测试函数（可选，用于调试）
-- SELECT to_tsvector('chinese', '这是一个测试文档内容') @@ to_tsquery('chinese', '测试 & 文档');

-- 6. 全文检索性能监控
-- SELECT relname, relkind, pg_size_pretty(pg_relation_size(oid)) 
-- FROM pg_class WHERE relname LIKE '%vector_store%';

-- 7. 索引重建（如果需要优化性能）
-- REINDEX INDEX idx_vs_text_gin;
-- REINDEX INDEX idx_vs_embedding;

-- ============================================================
-- 8. 更新时间触发器
-- ============================================================
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER trg_kb_document_updated_at
    BEFORE UPDATE ON kb_document
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- ============================================================
-- 9. MCP 工具调用审计日志表
-- 记录每次配置类操作的执行历史，便于追溯和排障
-- ============================================================
CREATE TABLE IF NOT EXISTS mcp_call_log (
    id           UUID DEFAULT uuid_generate_v4() PRIMARY KEY,
    query        TEXT         NOT NULL,              -- 用户原始需求
    intent_type  VARCHAR(32)  NOT NULL,              -- QUERY / CONFIG
    intent_conf  DECIMAL(5,4) DEFAULT 0.0,           -- 意图识别置信度
    intent_src   VARCHAR(16)  DEFAULT 'UNKNOWN',     -- BERT / LLM / FALLBACK
    plan_steps   JSONB,                              -- Planner 生成的步骤列表
    exec_result  TEXT,                               -- 执行结果摘要
    status       VARCHAR(32)  DEFAULT 'PENDING',     -- PENDING / SUCCESS / FAILED / PARTIAL
    error_msg    TEXT,                               -- 异常信息
    created_at   TIMESTAMP    DEFAULT NOW(),
    updated_at   TIMESTAMP    DEFAULT NOW()
);

-- 按意图类型和时间查询优化
CREATE INDEX IF NOT EXISTS idx_mcp_call_log_intent_type ON mcp_call_log(intent_type);
CREATE INDEX IF NOT EXISTS idx_mcp_call_log_created_at  ON mcp_call_log(created_at DESC);

-- 触发器：自动更新 updated_at
CREATE OR REPLACE TRIGGER trg_mcp_call_log_updated_at
    BEFORE UPDATE ON mcp_call_log
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- ============================================================
-- 10. SOP 向量存储表（由 Spring AI PgVectorStore 自动创建）
-- sop_vector_store 表将在首次启动时通过 initialize-schema=true 自动建立
-- 以下注释仅用于文档说明，无需手动执行
-- ============================================================
-- Spring AI 自动创建：
-- CREATE TABLE sop_vector_store (
--     id        UUID DEFAULT uuid_generate_v4() PRIMARY KEY,
--     content   TEXT,
--     metadata  JSON,
--     embedding vector(1536)
-- );
-- CREATE INDEX ON sop_vector_store USING HNSW (embedding vector_cosine_ops);
