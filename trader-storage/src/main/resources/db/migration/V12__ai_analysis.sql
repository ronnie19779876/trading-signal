-- 第 4 期·步骤 4：模型对信号候选的第二意见（只有否决权）。设计与实测见 docs/ARCHITECTURE.md §18.8。
-- 每次调用（含因预算跳过、输入构建失败）一行；同一标的同一天同一提示词同一模型同一输入哈希已有 OK 的直接复用，不再调用。

CREATE TABLE ai_analysis (
    id                bigserial     PRIMARY KEY,
    instrument_id     bigint        NOT NULL REFERENCES instrument (id),
    trade_date        date          NOT NULL,                  -- 输入数据的判定日
    purpose           varchar(12)   NOT NULL CHECK (purpose IN ('SIGNAL_VETO', 'MANUAL')),
    signal_id         bigint        REFERENCES entry_signal (id),
    prompt_version    varchar(32)   NOT NULL,
    model             varchar(64)   NOT NULL,
    reasoning_effort  varchar(12),
    input_hash        varchar(64)   NOT NULL,                  -- 发给模型的 JSON 原文的 SHA-256（jsonb 会重排键，哈希按原文算）
    input             jsonb         NOT NULL,
    status            varchar(16)   NOT NULL CHECK (status IN ('OK', 'REFUSED', 'TRUNCATED', 'INVALID', 'FAILED',
                                                               'SKIPPED_BUDGET', 'FAILED_DATA')),
    judgment          jsonb,                                   -- 结构化结论（OK 时）
    output_text       text,                                    -- 模型原文（结构非法、拒答时排查用）
    stance            varchar(8),
    confidence        varchar(8),
    verdict           varchar(8)    NOT NULL CHECK (verdict IN ('VETO', 'ALLOW', 'ABSENT')),
    verdict_reason    text,
    checks            jsonb,                                   -- 逐条证据核对结果
    verified_bear     integer,
    unverified        integer,
    error             text,
    response_id       varchar(128),
    input_tokens      integer,
    cached_tokens     integer,
    output_tokens     integer,
    reasoning_tokens  integer,
    latency_ms        integer,
    job_run_id        bigint,
    created_at        timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX ai_analysis_lookup_idx ON ai_analysis (instrument_id, trade_date, prompt_version);
CREATE INDEX ai_analysis_created_idx ON ai_analysis (created_at DESC);
COMMENT ON TABLE ai_analysis IS '模型第二意见：VETO 否决 / ALLOW 放行 / ABSENT 没有结论（失败、拒答、截断、预算跳过都算，不阻断信号）';
