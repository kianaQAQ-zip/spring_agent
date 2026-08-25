-- ============================================================
-- 电商客服 Agent 初始化脚本
-- 在 PostgreSQL 17 + pgvector 上执行（前置：已安装 pgvector 扩展）
-- 安装 pgvector 后，本脚本首行 CREATE EXTENSION 才会成功。
-- ============================================================

-- 向量扩展（必须，PgVector 依赖）。若报 "could not open extension control file"，
-- 说明 D:\PostgreSQL 未带 pgvector，需先安装匹配 PG17 的 pgvector 二进制。
CREATE EXTENSION IF NOT EXISTS vector;

-- ------------------------------------------------------------
-- 1) Spring AI 默认向量表（embedding 维度与 text-embedding-v3 一致 = 1024）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS vector_store (
    id       UUID PRIMARY KEY,
    content  TEXT,
    metadata JSONB,
    embedding vector(1024)
);
CREATE INDEX IF NOT EXISTS vector_store_embedding_idx
    ON vector_store USING hnsw (embedding vector_cosine_ops);

-- ------------------------------------------------------------
-- 2) 结构化会话状态（§8 Layer 2）：intent / order_id / emotion ...
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS session_state (
    id         UUID PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    tenant_id  VARCHAR(64) NOT NULL DEFAULT 'default',
    state_json JSONB NOT NULL,
    version    BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (session_id, tenant_id)
);

-- ------------------------------------------------------------
-- 3) 会话
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS conversation (
    id          UUID PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    tenant_id   VARCHAR(64) NOT NULL DEFAULT 'default',
    title       VARCHAR(255),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ------------------------------------------------------------
-- 4) 消息（PII 落库前脱敏）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS message (
    id          UUID PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    tenant_id   VARCHAR(64) NOT NULL DEFAULT 'default',
    role        VARCHAR(16) NOT NULL,
    content     TEXT,
    pii_masked  BOOLEAN NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_message_conv ON message (conversation_id);

-- ------------------------------------------------------------
-- 5) 待确认动作（§2 确认护栏）：pending/confirmed/rejected
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pending_action (
    id           UUID PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    tool         VARCHAR(64) NOT NULL,
    params       JSONB,
    status       VARCHAR(16) NOT NULL DEFAULT 'pending',
    final_params JSONB,
    operator     VARCHAR(64),
    executed_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_pending_status ON pending_action (conversation_id, status);

-- ------------------------------------------------------------
-- 6) 知识文档（Tika/MinerU 解析全文，供 M5 点击看原文）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS knowledge_doc (
    id           UUID PRIMARY KEY,
    doc_id       VARCHAR(64) NOT NULL,
    tenant_id    VARCHAR(64) NOT NULL DEFAULT 'default',
    source       VARCHAR(512),
    chunk_count  INT NOT NULL DEFAULT 0,
    parsed_text  TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
