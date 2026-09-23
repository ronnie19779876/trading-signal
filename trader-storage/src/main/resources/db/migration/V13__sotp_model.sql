-- 第 5 期：分部估值（SOTP）的假设。设计见 docs/ARCHITECTURE.md §21。
-- 一只标的可以存多套（"2030 基准"、"保守口径"），按 name 区分。
-- 业务线连同三情景整体存 jsonb：这套东西永远整体存整体取，不需要跨标的按字段查
-- （全市场"隐含增长"筛选列是另一件事，不依赖这张表）；项目已有先例 signal_evaluation.detail。

CREATE TABLE sotp_model (
    id              bigserial     PRIMARY KEY,
    instrument_id   bigint        NOT NULL REFERENCES instrument (id) ON DELETE CASCADE,
    name            varchar(64)   NOT NULL,                  -- 同一标的下唯一
    as_of           date          NOT NULL,                  -- 估值基准日，折现从这天算起
    target_year     integer       NOT NULL,
    discount_rate   numeric(8,6)  NOT NULL,                  -- 要求回报率，小数（10% 存 0.10）
    target_shares   numeric(24,4) NOT NULL,                  -- 目标年股数，不默认等于当前
    target_net_cash numeric(24,4) NOT NULL,                  -- 目标年净现金，可为负
    segments        jsonb         NOT NULL,                  -- [{name, scopeNote, cases:{BEAR,BASE,BULL:{volume,price,netMargin,pe}}}]
    note            text,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (instrument_id, name)
);

CREATE INDEX sotp_model_instrument_idx ON sotp_model (instrument_id, updated_at DESC);
