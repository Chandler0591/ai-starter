-- 用户 / 会话 / 知识库 / 示例业务表
-- 向量表由 Spring AI PgVectorStore 依据 spring.ai.vectorstore.pgvector.initialize-schema 自动创建

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE app_user (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    display_name  VARCHAR(64),
    role          VARCHAR(32)  NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE knowledge_doc (
    id          BIGSERIAL PRIMARY KEY,
    title       VARCHAR(200) NOT NULL,
    source      VARCHAR(64)  NOT NULL,
    content     TEXT         NOT NULL,
    chunk_count INT          NOT NULL DEFAULT 0,
    status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_by  BIGINT       REFERENCES app_user (id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE chat_session (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES app_user (id),
    title       VARCHAR(200),
    prompt_key  VARCHAR(64),
    prompt_ver  VARCHAR(16),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE chat_message (
    id          BIGSERIAL PRIMARY KEY,
    session_id  BIGINT      NOT NULL REFERENCES chat_session (id) ON DELETE CASCADE,
    role        VARCHAR(16) NOT NULL,
    content     TEXT        NOT NULL,
    trace_id    VARCHAR(32),
    tokens      INT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_chat_message_session ON chat_message (session_id, id);

-- 示例业务表：AI 工具调用（Tool Calling）据此查询真实业务数据
CREATE TABLE orders (
    id          BIGSERIAL PRIMARY KEY,
    order_no    VARCHAR(32)  NOT NULL UNIQUE,
    user_id     BIGINT       NOT NULL REFERENCES app_user (id),
    product     VARCHAR(120) NOT NULL,
    amount      NUMERIC(12, 2) NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_orders_user ON orders (user_id, created_at DESC);
