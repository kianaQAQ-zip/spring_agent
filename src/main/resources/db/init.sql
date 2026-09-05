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
    id         VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    tenant_id  VARCHAR(64) NOT NULL DEFAULT 'default',
    state_json TEXT NOT NULL,
    version    BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (session_id, tenant_id)
);

-- ------------------------------------------------------------
-- 3) 会话
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS conversation (
    id          VARCHAR(64) PRIMARY KEY,
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
    id          VARCHAR(64) PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    tenant_id   VARCHAR(64) NOT NULL DEFAULT 'default',
    role        VARCHAR(16) NOT NULL,
    content     TEXT,
    pii_masked  BOOLEAN NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_message_conv ON message (conversation_id);

-- ------------------------------------------------------------
-- 5) 待确认动作（§2 确认护栏）：pending/confirmed/rejected/cancelled/expired
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pending_action (
    id              VARCHAR(64) PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    tenant_id       VARCHAR(64) NOT NULL DEFAULT 'default',
    tool            VARCHAR(64) NOT NULL,
    params          TEXT,
    status          VARCHAR(16) NOT NULL DEFAULT 'pending',
    idempotency_key VARCHAR(64) NOT NULL,
    final_params    TEXT,
    operator        VARCHAR(64),
    executed_at     TIMESTAMPTZ,
    result          TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL DEFAULT now() + interval '5 minutes'
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_pending_idem
    ON pending_action (conversation_id, idempotency_key);
CREATE INDEX IF NOT EXISTS idx_pending_status ON pending_action (conversation_id, status);

-- ------------------------------------------------------------
-- 6) 知识文档（Tika/MinerU 解析全文，供 M5 点击看原文）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS knowledge_doc (
    id           VARCHAR(64) PRIMARY KEY,
    doc_id       VARCHAR(64) NOT NULL,
    tenant_id    VARCHAR(64) NOT NULL DEFAULT 'default',
    source       VARCHAR(512),
    chunk_count  INT NOT NULL DEFAULT 0,
    parsed_text  TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ------------------------------------------------------------
-- 7) 订单主表（真实数据源）
--    业务前提：单商家多平台 —— tenant 固定 default，platform 是独立维度。
--    一张订单只属于一个平台；统计时按 (tenant_id, platform, created_at) 聚合。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS orders (
    id           VARCHAR(64) PRIMARY KEY,
    order_id     VARCHAR(64) NOT NULL,
    tenant_id    VARCHAR(64) NOT NULL DEFAULT 'default',
    platform     VARCHAR(32) NOT NULL DEFAULT 'unknown',
    buyer_name   VARCHAR(64),
    buyer_phone  VARCHAR(32),
    status       VARCHAR(32) NOT NULL,
    amount       NUMERIC(10,2) NOT NULL DEFAULT 0,
    item_title   VARCHAR(255),
    quantity     INT NOT NULL DEFAULT 1,
    address      TEXT,
    carrier      VARCHAR(64),
    tracking_no  VARCHAR(64),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, order_id)
);
CREATE INDEX IF NOT EXISTS idx_orders_platform_time
    ON orders (tenant_id, platform, created_at);
CREATE INDEX IF NOT EXISTS idx_orders_status
    ON orders (tenant_id, status);

-- ------------------------------------------------------------
-- 8) 物流轨迹（一条订单多条，按 seq 倒序取最新）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS order_trace (
    id          VARCHAR(64) PRIMARY KEY,
    order_id    VARCHAR(64) NOT NULL,
    tenant_id   VARCHAR(64) NOT NULL DEFAULT 'default',
    platform    VARCHAR(32) NOT NULL DEFAULT 'unknown',
    seq         INT NOT NULL DEFAULT 0,
    happened_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    node        TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_trace_order ON order_trace (tenant_id, order_id, seq DESC);

-- ------------------------------------------------------------
-- 9) 会话 / 消息补齐平台维度（Q2 人工标注 + Q3 单商家多平台）
--    索引服务后续按「平台 + 时间」聚合的统计查询。
-- ------------------------------------------------------------
ALTER TABLE conversation ADD COLUMN IF NOT EXISTS platform VARCHAR(32) NOT NULL DEFAULT 'unknown';
ALTER TABLE message      ADD COLUMN IF NOT EXISTS platform VARCHAR(32) NOT NULL DEFAULT 'unknown';
CREATE INDEX IF NOT EXISTS idx_conv_platform_time ON conversation (tenant_id, platform, created_at);
CREATE INDEX IF NOT EXISTS idx_msg_platform_time  ON message (tenant_id, platform, created_at);
-- 会话幂等：同租户下 conversation_id 唯一，供 upsert 的 ON CONFLICT 使用
CREATE UNIQUE INDEX IF NOT EXISTS idx_conv_unique
    ON conversation (tenant_id, conversation_id);

-- ------------------------------------------------------------
-- 10) RAG 评估快照（§9 线上指标：命中率 / 引用准确率 / 成本，全部真实对话产生）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rag_eval (
    id              VARCHAR(64) PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    tenant_id       VARCHAR(64) NOT NULL DEFAULT 'default',
    platform        VARCHAR(32) NOT NULL DEFAULT 'unknown',
    query           TEXT,
    hit             BOOLEAN NOT NULL,
    doc_count       INT NOT NULL DEFAULT 0,
    citation_count  INT NOT NULL DEFAULT 0,
    out_of_range    INT NOT NULL DEFAULT 0,
    answer_tokens   INT NOT NULL DEFAULT 0,
    cost            NUMERIC(12,6) NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_rag_eval_time ON rag_eval (tenant_id, created_at);

-- ------------------------------------------------------------
-- 11) 转人工工单（M3）：Agent 搞不定时带上下文交接给人工
--     context 存对话历史快照 JSON，避免会话被清理后工单失去上下文
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS handoff_ticket (
    id              VARCHAR(64) PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    tenant_id       VARCHAR(64) NOT NULL DEFAULT 'default',
    platform        VARCHAR(32) NOT NULL DEFAULT 'unknown',
    reason          VARCHAR(64) NOT NULL,
    detail          TEXT,
    context         TEXT,
    status          VARCHAR(16) NOT NULL DEFAULT 'open',
    operator        VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_at      TIMESTAMPTZ,
    closed_at       TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_handoff_status ON handoff_ticket (tenant_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_handoff_conv   ON handoff_ticket (tenant_id, conversation_id);

-- ------------------------------------------------------------
-- 12) 优惠券（M4）：坐席确认后真实发放，替代 mock
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS coupon (
    id          VARCHAR(64) PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL DEFAULT 'default',
    coupon_type VARCHAR(32) NOT NULL,
    value       NUMERIC(10,2) NOT NULL DEFAULT 0,
    status      VARCHAR(16) NOT NULL DEFAULT 'issued',
    issued_by   VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_coupon_tenant ON coupon (tenant_id, created_at DESC);

-- ------------------------------------------------------------
-- 13) 未命中问题语义簇（M4）：embedding 贪心聚类结果，供缺口雷达读
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rag_gap_cluster (
    id             VARCHAR(64) PRIMARY KEY,
    tenant_id      VARCHAR(64) NOT NULL DEFAULT 'default',
    representative TEXT NOT NULL,
    member_count   INT NOT NULL DEFAULT 1,
    last_seen      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_gap_cluster_tenant ON rag_gap_cluster (tenant_id, member_count DESC);

-- ------------------------------------------------------------
-- 14) 知识库管理（M5 运营）：多知识库 + 文档管理字段
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS knowledge_base (
    id              VARCHAR(64) PRIMARY KEY,
    kb_id           VARCHAR(64) NOT NULL,
    tenant_id       VARCHAR(64) NOT NULL DEFAULT 'default',
    name            VARCHAR(128) NOT NULL,
    description     VARCHAR(512),
    embedding_model VARCHAR(64) NOT NULL DEFAULT 'text-embedding-v3',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_kb UNIQUE (tenant_id, kb_id)
);
-- 内置默认知识库（幂等种子）
INSERT INTO knowledge_base (id, kb_id, name, description)
SELECT 'kb-default-seed', 'default', '默认知识库', '系统内置知识库（上传未指定知识库时归入此处）'
WHERE NOT EXISTS (SELECT 1 FROM knowledge_base WHERE tenant_id = 'default' AND kb_id = 'default');

ALTER TABLE knowledge_doc ADD COLUMN IF NOT EXISTS kb_id     VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE knowledge_doc ADD COLUMN IF NOT EXISTS file_size BIGINT;
ALTER TABLE knowledge_doc ADD COLUMN IF NOT EXISTS status    VARCHAR(16) NOT NULL DEFAULT 'INGESTED';
ALTER TABLE knowledge_doc ADD COLUMN IF NOT EXISTS clean_score NUMERIC(6,4);
CREATE INDEX IF NOT EXISTS idx_kdoc_kb     ON knowledge_doc (tenant_id, kb_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_kdoc_status ON knowledge_doc (tenant_id, status);
