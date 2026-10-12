-- 量化选股·模拟交易（ARCHITECTURE §26，3.4.0）。不下单、不连网关：只用库里已有的日 K 记账。
-- 规则版本 sim-v1（2026-10-12 用户拍板）：等权、止损距离封顶 10%、没有上方压力区用 +2R、成本按 0。
-- 价格都是评分日口径（评分日当天的原始价）；持有期间拆股时价格与股数同比换算，金额不变。

CREATE TABLE selection_sim_account (
    id               smallint      PRIMARY KEY CHECK (id = 1),
    rules_version    varchar(16)   NOT NULL,
    initial_capital  numeric(14,2) NOT NULL,
    created_at       timestamptz   NOT NULL DEFAULT now()
);
INSERT INTO selection_sim_account (id, rules_version, initial_capital) VALUES (1, 'sim-v1', 500000);
COMMENT ON TABLE selection_sim_account IS '模拟账户（只有一个）；现金与权益不存，由各笔成交推出来';

CREATE TABLE selection_sim_trade (
    id                  bigserial     PRIMARY KEY,
    rules_version       varchar(16)   NOT NULL,
    instrument_id       bigint        NOT NULL REFERENCES instrument (id),
    -- 加入时的评分（加入理由）
    model_version       varchar(8)    NOT NULL,
    score_date          date          NOT NULL,
    score_rank          integer       NOT NULL,
    rating              varchar(12),
    composite           numeric(6,2),
    requested_at        timestamptz   NOT NULL,
    -- 加入时定下、之后不变的价位（评分日口径）
    setup_close         numeric(18,6) NOT NULL,
    setup_atr           numeric(18,6) NOT NULL,
    formula_stop        numeric(18,6) NOT NULL,
    stop                numeric(18,6) NOT NULL,
    stop_leg            varchar(8)    NOT NULL CHECK (stop_leg IN ('ATR', 'ZONE', 'CAP')),
    resistance          numeric(18,6),
    planned_entry_date  date          NOT NULL,
    -- 状态
    status              varchar(16)   NOT NULL CHECK (status IN ('PENDING_ENTRY', 'OPEN', 'CLOSED', 'CANCELLED')),
    entry_date          date,
    entry_price         numeric(18,6),
    shares              integer       CHECK (shares IS NULL OR shares > 0),
    plus_one_r          numeric(18,6),
    target              numeric(18,6),
    target_source       varchar(12)   CHECK (target_source IN ('RESISTANCE', 'FALLBACK_2R')),
    touched_plus_one_r  boolean       NOT NULL DEFAULT false,
    target_hit_date     date,
    active_stop         numeric(18,6),
    chandelier_active   boolean       NOT NULL DEFAULT false,
    -- 手动平仓请求（止盈提示后或任意时候）
    close_requested_at  timestamptz,
    planned_exit_date   date,
    close_kind          varchar(12)   CHECK (close_kind IN ('TAKE_PROFIT', 'MANUAL')),
    close_note          text,
    -- 结果
    exit_date           date,
    exit_price          numeric(18,6),
    exit_reason         varchar(12)   CHECK (exit_reason IN ('STOP', 'CHANDELIER', 'TAKE_PROFIT', 'MANUAL')),
    pnl                 numeric(14,2),
    return_pct          numeric(12,6),
    r_multiple          numeric(12,6),
    mfe_r               numeric(12,6),
    mae_r               numeric(12,6),
    bars_held           integer,
    last_close          numeric(18,6),
    last_close_date     date,
    unrealized_pnl      numeric(14,2),
    spy_return          numeric(12,6),
    excess_return       numeric(12,6),
    -- 影子变体：同一笔、同样股数，但不做任何手动平仓，只按止损 / 吊灯止损走（与实际操作配对比较手动止盈的得失）
    shadow_status       varchar(8)    CHECK (shadow_status IN ('OPEN', 'CLOSED')),
    shadow_exit_date    date,
    shadow_exit_price   numeric(18,6),
    shadow_exit_reason  varchar(12)   CHECK (shadow_exit_reason IN ('STOP', 'CHANDELIER')),
    shadow_pnl          numeric(14,2),
    shadow_return       numeric(12,6),
    shadow_last_close   numeric(18,6),
    cancel_reason       text,
    updated_through     date,
    updated_at          timestamptz   NOT NULL DEFAULT now()
);
-- 同一只同时只能有一笔在进行中（待成交或持有）
CREATE UNIQUE INDEX selection_sim_trade_active_idx ON selection_sim_trade (instrument_id) WHERE status IN ('PENDING_ENTRY', 'OPEN');
CREATE INDEX selection_sim_trade_status_idx ON selection_sim_trade (status);
COMMENT ON TABLE selection_sim_trade IS '模拟交易逐笔；未完结的（含影子变体）每天从加入时的价位整段重算';
COMMENT ON COLUMN selection_sim_trade.stop_leg IS 'ATR = 收盘 − 2×ATR；ZONE = 支撑区底 − 0.5×ATR；CAP = 公式止损距离超过 10%，封顶';
COMMENT ON COLUMN selection_sim_trade.spy_return IS 'SPY 同一持有期的收益：入场日开盘 → 平仓价同一时点（开盘或收盘）；未平仓到最近收盘';
COMMENT ON COLUMN selection_sim_trade.shadow_pnl IS '影子变体盈亏；影子还没平仓时为 null，看 shadow_last_close';

-- 操作留痕：只追加，不改
CREATE TABLE selection_sim_event (
    id        bigserial    PRIMARY KEY,
    trade_id  bigint       REFERENCES selection_sim_trade (id),
    at        timestamptz  NOT NULL DEFAULT now(),
    kind      varchar(24)  NOT NULL,
    detail    text         NOT NULL
);
CREATE INDEX selection_sim_event_trade_idx ON selection_sim_event (trade_id, at);

-- 逐日权益（收盘后）：由成交推出来写入，周报的回撤与收益从这里取
CREATE TABLE selection_sim_equity (
    trade_date       date          PRIMARY KEY,
    cash             numeric(14,2) NOT NULL,
    positions_value  numeric(14,2) NOT NULL,
    equity           numeric(14,2) NOT NULL,
    open_positions   integer       NOT NULL,
    computed_at      timestamptz   NOT NULL DEFAULT now()
);

-- 每周报表快照：生成后不变（重新生成要显式 force，并留痕）
CREATE TABLE selection_sim_weekly (
    week_end          date          PRIMARY KEY,
    week_start        date          NOT NULL,
    rules_version     varchar(16)   NOT NULL,
    equity_end        numeric(14,2) NOT NULL,
    week_return       numeric(12,6),
    spy_week_return   numeric(12,6),
    closed_trades     integer       NOT NULL,
    report            jsonb         NOT NULL,
    generated_at      timestamptz   NOT NULL DEFAULT now()
);
