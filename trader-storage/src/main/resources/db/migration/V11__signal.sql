-- 第 4 期·步骤 3：入场哨兵的每日评估、信号、纸面跟踪账本。设计见 docs/ARCHITECTURE.md §18。

CREATE TABLE signal_evaluation (
    instrument_id        bigint        NOT NULL REFERENCES instrument (id),
    trade_date           date          NOT NULL,
    ruleset_version      varchar(16)   NOT NULL,
    status               varchar(28)   NOT NULL CHECK (status IN ('EVALUATED', 'SKIPPED_INSUFFICIENT_BARS', 'SKIPPED_STALE_DATA',
                                                                  'SKIPPED_DATA_GAP', 'SKIPPED_CORPORATE_ACTION')),
    status_detail        text,
    outcome              varchar(20)   CHECK (outcome IN ('SIGNAL', 'NO_SIGNAL', 'SUPPRESSED_EDGE', 'SUPPRESSED_COOLDOWN', 'BLOCKED_BY_AI')),
    gates                varchar(4),                        -- 趋势/定位/触发/风控：P 通过 F 不过 U 不可判定；不予判定时 NULL
    gates_passed         smallint      NOT NULL DEFAULT 0,
    first_blocking_gate  varchar(8),
    role                 varchar(8)    NOT NULL CHECK (role IN ('POOL', 'HOLDING', 'UNIVERSE')),
    close                numeric(18,6),
    atr14                numeric(18,6),
    rvol                 numeric(12,6),
    zone_bottom          numeric(18,6),
    stop                 numeric(18,6),
    stop_distance        numeric(10,6),
    input_fingerprint    varchar(32)   NOT NULL,
    detail               jsonb,                             -- 每道门的判据原文与代入值、支撑区、出场预案；只给池/持仓、≥3 门、非 NO_SIGNAL 的存
    job_run_id           bigint,
    evaluated_at         timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (instrument_id, trade_date, ruleset_version)
);
CREATE INDEX signal_evaluation_date_idx ON signal_evaluation (trade_date, ruleset_version);
COMMENT ON TABLE signal_evaluation IS '入场哨兵每日评估，一只一天一个判据版本一行，重跑覆盖；价格为判定日口径（判定日那根即原始价）';
COMMENT ON COLUMN signal_evaluation.input_fingerprint IS '窗口内原始 K 线 + 结构性事件因子 + 判据版本的 SHA-256 前 32 位；复算不一致说明数据被重拉改过';

CREATE TABLE entry_signal (
    id                   bigserial     PRIMARY KEY,
    instrument_id        bigint        NOT NULL REFERENCES instrument (id),
    trade_date           date          NOT NULL,
    ruleset_version      varchar(16)   NOT NULL,
    role                 varchar(8)    NOT NULL CHECK (role IN ('POOL', 'HOLDING', 'UNIVERSE')),
    origin               varchar(8)    NOT NULL CHECK (origin IN ('LIVE', 'BACKFILL')),
    close                numeric(18,6) NOT NULL,
    atr14                numeric(18,6) NOT NULL,
    stop                 numeric(18,6) NOT NULL,
    stop_leg             varchar(4)    NOT NULL CHECK (stop_leg IN ('ATR', 'ZONE')),
    stop_distance        numeric(10,6) NOT NULL,
    risk_per_share       numeric(18,6) NOT NULL,
    plus_one_r           numeric(18,6) NOT NULL,
    chandelier_stop      numeric(18,6),
    target               numeric(18,6),
    reward_risk          numeric(12,6),
    zone_bottom          numeric(18,6),
    zone_top             numeric(18,6),
    zone_touches         integer,
    bonus                jsonb,
    ai_analysis_id       bigint,                            -- 步骤 4
    ai_stance            varchar(12),
    status               varchar(12)   NOT NULL DEFAULT 'NEW'
                                       CHECK (status IN ('NEW', 'ACKNOWLEDGED', 'DISMISSED', 'EXPIRED', 'VETOED')),
    expires_on           date          NOT NULL,            -- 判定日后第 2 个交易日；该日收盘后的评估把 NEW / ACKNOWLEDGED 标为过期
    note                 text,
    status_changed_at    timestamptz,
    job_run_id           bigint,
    created_at           timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (instrument_id, trade_date, ruleset_version)
);
CREATE INDEX entry_signal_date_idx ON entry_signal (trade_date DESC);
COMMENT ON TABLE entry_signal IS '入场信号，只追加；价位为判定日口径（当天真实可成交价）。BACKFILL = 对过去日期补跑产生，统计时与实盘分开';

CREATE TABLE signal_track (
    signal_id            bigint        NOT NULL REFERENCES entry_signal (id),
    variant              varchar(12)   NOT NULL CHECK (variant IN ('BASE', 'STOP_2_5')),
    status               varchar(14)   NOT NULL CHECK (status IN ('PENDING_ENTRY', 'OPEN', 'CLOSED')),
    stop                 numeric(18,6) NOT NULL,
    plus_one_r           numeric(18,6) NOT NULL,
    entry_date           date,
    entry_price          numeric(18,6),
    touched_plus_one_r   boolean       NOT NULL DEFAULT false,
    exit_date            date,
    exit_price           numeric(18,6),
    exit_reason          varchar(12)   CHECK (exit_reason IN ('STOP', 'CHANDELIER', 'TIME')),
    r_multiple           numeric(12,6),
    return_pct           numeric(12,6),
    mfe_r                numeric(12,6),
    mae_r                numeric(12,6),
    bars_held            integer,
    updated_through      date,
    updated_at           timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (signal_id, variant)
);
CREATE INDEX signal_track_unfinished_idx ON signal_track (status) WHERE status <> 'CLOSED';
COMMENT ON TABLE signal_track IS '纸面跟踪账本：BASE = sentinel-v1 出场（止损 2.0×ATR、不减半仓），STOP_2_5 只把止损换成 2.5×ATR；未平仓每天从信号日整段重算；价格为判定日口径';
