-- 第 2 期·步骤 1：标的、指数成分、标的池、日 K 线（不复权）、复权因子、交易日历、同步状态、作业记录。

CREATE TABLE instrument (
    id              bigserial     PRIMARY KEY,
    market          varchar(4)    NOT NULL CHECK (market IN ('US', 'HK')),
    symbol          varchar(16)   NOT NULL,
    name            varchar(128),                       -- 英文名（成分股来源）
    name_cn         varchar(128),                       -- 券商给的中文名
    sec_type        varchar(8)    NOT NULL DEFAULT 'OTHER' CHECK (sec_type IN ('STOCK', 'ETF', 'OTHER')),
    lot_size        integer,
    list_date       date,
    delisted        boolean       NOT NULL DEFAULT false,
    exchange        varchar(16),
    broker_id       bigint,                             -- 富途内部 id
    resolve_status  varchar(12)   NOT NULL DEFAULT 'PENDING' CHECK (resolve_status IN ('PENDING', 'RESOLVED', 'UNRESOLVED')),
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (market, symbol)
);
COMMENT ON TABLE instrument IS '标的主表。永不删行：退出指数或退市只改状态';

CREATE TABLE index_constituent (
    index_code      varchar(8)    NOT NULL CHECK (index_code IN ('SP500', 'NDX100')),
    instrument_id   bigint        NOT NULL REFERENCES instrument (id),
    sector          varchar(64),
    sub_industry    varchar(96),
    classification  varchar(8),                         -- GICS / ICB
    since           date          NOT NULL,
    until           date,                               -- NULL = 现任成分
    source          varchar(32)   NOT NULL,
    PRIMARY KEY (index_code, instrument_id, since)
);
CREATE INDEX index_constituent_current_idx ON index_constituent (index_code) WHERE until IS NULL;
COMMENT ON TABLE index_constituent IS '指数成分股及其变更历史（since/until）';

CREATE TABLE pool_member (
    instrument_id   bigint        PRIMARY KEY REFERENCES instrument (id),
    role            varchar(8)    NOT NULL CHECK (role IN ('POOL', 'HOLDING')),
    note            text,
    added_at        timestamptz   NOT NULL DEFAULT now()
);
COMMENT ON TABLE pool_member IS '标的池：POOL 手工候选池（上限见配置），HOLDING 持仓（第 3 期由盈透自动维护）';

CREATE TABLE daily_bar (
    instrument_id   bigint        NOT NULL REFERENCES instrument (id),
    trade_date      date          NOT NULL,
    open            numeric(18,6) NOT NULL,
    high            numeric(18,6) NOT NULL,
    low             numeric(18,6) NOT NULL,
    close           numeric(18,6) NOT NULL,
    last_close      numeric(18,6),
    volume          bigint        NOT NULL,
    turnover        numeric(20,4),
    turnover_rate   numeric(12,6),
    change_rate     numeric(12,6),
    pe              numeric(12,4),
    blank           boolean       NOT NULL DEFAULT false,
    source          varchar(12)   NOT NULL CHECK (source IN ('FUTU_KL', 'FUTU_HIST')),
    fetched_at      timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (instrument_id, trade_date)
);
CREATE INDEX daily_bar_trade_date_idx ON daily_bar (trade_date);
COMMENT ON TABLE daily_bar IS '日 K 线，一律不复权；复权在读取层按 rehab_factor 计算';

CREATE TABLE rehab_factor (
    instrument_id     bigint        NOT NULL REFERENCES instrument (id),
    ex_date           date          NOT NULL,
    fwd_a             numeric(20,10) NOT NULL,
    fwd_b             numeric(20,10) NOT NULL,
    bwd_a             numeric(20,10) NOT NULL,
    bwd_b             numeric(20,10) NOT NULL,
    company_act_flag  bigint        NOT NULL DEFAULT 0,
    dividend          numeric(18,6),
    sp_dividend       numeric(18,6),
    split_base        integer       NOT NULL DEFAULT 0,
    split_ert         integer       NOT NULL DEFAULT 0,
    fetched_at        timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (instrument_id, ex_date)
);
COMMENT ON TABLE rehab_factor IS '除权除息事件的复权因子（富途口径）：前复权价 = 不复权价 × fwd_a + fwd_b';

CREATE TABLE trading_day (
    market      varchar(4)  NOT NULL,
    trade_date  date        NOT NULL,
    kind        smallint    NOT NULL DEFAULT 0,
    PRIMARY KEY (market, trade_date)
);

CREATE TABLE bar_sync_state (
    instrument_id       bigint        PRIMARY KEY REFERENCES instrument (id),
    depth               varchar(8)    NOT NULL CHECK (depth IN ('NONE', 'KL1000', 'HIST20Y')),
    earliest_date       date,
    latest_date         date,
    bar_count           integer       NOT NULL DEFAULT 0,
    last_success_at     timestamptz,
    last_error          text,
    hist_quota_used_at  timestamptz,
    updated_at          timestamptz   NOT NULL DEFAULT now()
);
COMMENT ON TABLE bar_sync_state IS '每个标的的 K 线同步状态：深度（订阅通道 1000 根 / 历史通道 20 年）、覆盖区间、最近错误';

CREATE TABLE job_run (
    id           bigserial    PRIMARY KEY,
    job          varchar(32)  NOT NULL,
    trigger      varchar(8)   NOT NULL CHECK (trigger IN ('MANUAL', 'SCHEDULE')),
    started_at   timestamptz  NOT NULL DEFAULT now(),
    finished_at  timestamptz,
    status       varchar(8)   NOT NULL CHECK (status IN ('RUNNING', 'OK', 'PARTIAL', 'FAILED')),
    summary      text
);
CREATE INDEX job_run_started_at_idx ON job_run (started_at DESC);
COMMENT ON TABLE job_run IS '跑批记录：成分股同步、全量轮转、深度回补、每日增量';
