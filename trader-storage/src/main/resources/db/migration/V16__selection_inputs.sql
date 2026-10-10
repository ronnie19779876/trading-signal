-- 第 6 期·量化选股（ARCHITECTURE §25）6.1：评分引擎的输入。
-- 量化选股是独立功能：表一律以 selection_ 开头，与入场信号（第 4 期）的表互不引用。
-- 深度财报不在这里，写进共用的 financial_report / financial_item（期别同键，幂等追加）。

CREATE TABLE selection_backfill_state (
    instrument_id  bigint        NOT NULL REFERENCES instrument (id),
    part           varchar(12)   NOT NULL CHECK (part IN ('PRICES', 'VALUATION', 'EARNINGS', 'FINANCIALS')),
    status         varchar(8)    NOT NULL CHECK (status IN ('OK', 'EMPTY', 'SKIPPED', 'FAILED')),
    detail         text,
    updated_at     timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (instrument_id, part)
);
COMMENT ON TABLE selection_backfill_state IS '输入回补进度：续跑时 OK / EMPTY / SKIPPED 跳过，FAILED 重取；force 全部重取';
COMMENT ON COLUMN selection_backfill_state.status IS 'EMPTY = 券商回了空（不是失败）；SKIPPED = 不需要（如富途日 K 已够深，不取盈透）';

CREATE TABLE selection_price_bar (
    instrument_id     bigint        NOT NULL REFERENCES instrument (id),
    trade_date        date          NOT NULL,
    open              numeric(18,6),
    high              numeric(18,6),
    low               numeric(18,6),
    close             numeric(18,6) NOT NULL,
    volume            bigint        NOT NULL,
    split_basis_date  date          NOT NULL,
    fetched_at        timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (instrument_id, trade_date)
);
COMMENT ON TABLE selection_price_bar IS '盈透 TRADES 日线原样：已按拆股调整、未按分红调整，与 daily_bar（富途不复权）口径相反，不得混用';
COMMENT ON COLUMN selection_price_bar.split_basis_date IS '抓取日：盈透按这天为止发生的拆股调整，之后再拆股要靠接缝比值折算';
COMMENT ON COLUMN selection_price_bar.volume IS '盈透成交量口径与富途不同（§24.4），量化选股不用';

CREATE TABLE selection_price_seam (
    instrument_id  bigint        PRIMARY KEY REFERENCES instrument (id),
    ibkr_con_id    bigint        NOT NULL,
    ibkr_from      date,
    ibkr_to        date,
    futu_from      date,
    overlap_days   integer       NOT NULL,
    compared_days  integer       NOT NULL,
    within_days    integer       NOT NULL,
    max_abs_diff   numeric(12,6),
    ok             boolean       NOT NULL,
    detail         text,
    checked_at     timestamptz   NOT NULL DEFAULT now()
);
COMMENT ON TABLE selection_price_seam IS '盈透与富途重叠段的日收益核对；不通过的标的在需要盈透段的日子动能记缺';

CREATE TABLE selection_valuation (
    instrument_id  bigint        NOT NULL REFERENCES instrument (id),
    trade_date     date          NOT NULL,
    pe_ttm         numeric(18,4),
    pb             numeric(18,4),
    ps             numeric(18,4),
    source         varchar(8)    NOT NULL CHECK (source IN ('HIST', 'SNAPSHOT', 'CARRIED')),
    fetched_at     timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (instrument_id, trade_date)
);
CREATE INDEX selection_valuation_date_idx ON selection_valuation (trade_date);
COMMENT ON TABLE selection_valuation IS '逐日估值：HIST = 富途估值历史（近似时点口径），SNAPSHOT = 每日估值快照续接，CARRIED = PS 按收盘价比例顺延';
COMMENT ON COLUMN selection_valuation.pe_ttm IS '与估值快照 pe_ttm 同口径；亏损股为负是真实数据';

CREATE TABLE selection_earnings_event (
    instrument_id  bigint        NOT NULL REFERENCES instrument (id),
    period_text    varchar(16)   NOT NULL,
    fiscal_year    integer,
    pub_date       date          NOT NULL,
    pub_time_text  varchar(32),
    timing         varchar(16)   NOT NULL CHECK (timing IN ('PRE_MARKET', 'DURING_MARKET', 'AFTER_MARKET', 'UNKNOWN')),
    is_current     boolean       NOT NULL DEFAULT false,
    fetched_at     timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (instrument_id, period_text)
);
COMMENT ON TABLE selection_earnings_event IS '财报公告日（富途只给最近 20 季）。含未来的预定日期：pub_date 晚于判定日的不算已公告';
COMMENT ON COLUMN selection_earnings_event.pub_time_text IS '券商时刻原文，时区未经证实，只留档';
