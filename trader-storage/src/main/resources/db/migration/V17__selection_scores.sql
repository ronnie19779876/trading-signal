-- 第 6 期·量化选股（ARCHITECTURE §25）6.2：证券类型、评分结果。

-- 证券类型来自盈透合约明细 stockType（2026-10-12 实测 494 只：COMMON 485、ADR 3、REIT 3、NY REG SHRS 1）。
-- ADR、NY REG SHRS、REIT 不纳入打分（用户 2026-10-11 / 10-12 拍板）；查不到的不猜，同样排除。
CREATE TABLE selection_security (
    instrument_id  bigint        PRIMARY KEY REFERENCES instrument (id),
    security_type  varchar(24),
    ibkr_con_id    bigint,
    checked_at     timestamptz   NOT NULL DEFAULT now()
);
COMMENT ON TABLE selection_security IS '盈透合约明细的证券类型；富途把部分 REITs 记成普通股（DOC、WELL、VMRK），排除 REITs 以这里为准';

ALTER TABLE selection_backfill_state DROP CONSTRAINT selection_backfill_state_part_check;
ALTER TABLE selection_backfill_state ADD CONSTRAINT selection_backfill_state_part_check
    CHECK (part IN ('PRICES', 'VALUATION', 'EARNINGS', 'FINANCIALS', 'TYPES'));

-- 评分结果：每天每只一行，带模型版本（改参数 = 换版本，历史结果不被覆盖）。重放与每日作业写同一张表。
CREATE TABLE selection_score (
    model_version   varchar(8)    NOT NULL,
    trade_date      date          NOT NULL,
    instrument_id   bigint        NOT NULL REFERENCES instrument (id),
    sector          varchar(64),
    exclusion       varchar(24),
    exclusion_detail text,
    value_score     numeric(6,2),
    growth_score    numeric(6,2),
    profit_score    numeric(6,2),
    momentum_score  numeric(6,2),
    composite       numeric(6,2),
    composite_pct   numeric(6,2),
    rating          varchar(12)   CHECK (rating IN ('STRONG_BUY', 'BUY', 'HOLD', 'SELL', 'STRONG_SELL')),
    sb_streak       integer       NOT NULL DEFAULT 0,
    metrics         jsonb         NOT NULL,
    fingerprint     varchar(32)   NOT NULL,
    computed_at     timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (model_version, trade_date, instrument_id)
);
CREATE INDEX selection_score_instrument_idx ON selection_score (model_version, instrument_id, trade_date);
COMMENT ON TABLE selection_score IS '量化选股评分；exclusion 非空 = 当天不参与，rating 为空 = 未评级（被排除或因子不齐）';
COMMENT ON COLUMN selection_score.metrics IS '{指标: [原值, 板块内百分位]}，原值缺失为 null';
COMMENT ON COLUMN selection_score.sb_streak IS '截至当天连续 STRONG_BUY 的交易日数（第 3 步「持续 N 天才触发」的输入）';
COMMENT ON COLUMN selection_score.fingerprint IS '输入指纹（板块、排除原因、各指标原值），重算不一致说明输入被改过';
