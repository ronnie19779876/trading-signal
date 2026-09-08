-- 第 2 期·步骤 3：估值快照、财务报表、公司简介。
-- 取值口径以对真实 OpenD 的实测为准：亏损股的市盈率市净率是负数（真实数据，不加非负约束）；
-- ETF 没有市盈率市净率、个股没有净值，两套口径共用一张表，缺的一律留 NULL。

CREATE TABLE valuation_snapshot (
    instrument_id       bigint        NOT NULL REFERENCES instrument (id),
    trade_date          date          NOT NULL,
    as_of               timestamptz   NOT NULL,           -- 券商给的行情时刻，判断这批数字属于盘中还是收盘
    suspended           boolean       NOT NULL DEFAULT false,
    market_cap          numeric(24,4),                    -- 总市值（ETF 无）
    float_market_cap    numeric(24,4),
    issued_shares       bigint,
    outstanding_shares  bigint,                           -- ETF 记份额
    pe                  numeric(18,4),                    -- 静态市盈率，亏损股为负
    pe_ttm              numeric(18,4),
    pb                  numeric(18,4),                    -- 资不抵债时为负
    eps                 numeric(18,4),
    net_asset_per_share numeric(18,4),
    net_asset           numeric(24,4),                    -- ETF 记资产规模
    net_profit          numeric(24,4),
    dividend_ttm        numeric(18,4),                    -- 0 表示不分红，是真实值
    dividend_yield_ttm  numeric(12,4),
    turnover_rate       numeric(12,6),
    nav_per_share       numeric(18,4),                    -- ETF 净值；富途对多数美股 ETF 不给，留 NULL
    premium             numeric(12,4),                    -- ETF 溢价，折价为负；净值缺失时一并留 NULL
    fetched_at          timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (instrument_id, trade_date)
);
CREATE INDEX valuation_snapshot_date_idx ON valuation_snapshot (trade_date);
COMMENT ON TABLE valuation_snapshot IS '估值快照，一只一天一行，可重跑覆盖；市值随价格变，收盘后取才代表当日';
COMMENT ON COLUMN valuation_snapshot.pe IS '静态市盈率；亏损股为负是真实数据，不是异常';
COMMENT ON COLUMN valuation_snapshot.nav_per_share IS 'ETF 净值；实测富途对多数美股 ETF 返回 0，已在网关层转为 NULL';

CREATE TABLE financial_report (
    id                   bigserial     PRIMARY KEY,
    instrument_id        bigint        NOT NULL REFERENCES instrument (id),
    statement            varchar(16)   NOT NULL CHECK (statement IN ('INCOME', 'BALANCE_SHEET', 'CASH_FLOW', 'MAIN_INDEX')),
    period_end           date          NOT NULL,
    period_text          varchar(16)   NOT NULL,          -- 2026/FY、2027/Q2
    fiscal_year          integer,                         -- 可能领先自然年，排序不要用它
    currency             varchar(8),
    accounting_standards varchar(32),
    auditor_report       varchar(64),
    fetched_at           timestamptz   NOT NULL DEFAULT now(),
    UNIQUE (instrument_id, statement, period_end, period_text)
);
CREATE INDEX financial_report_lookup_idx ON financial_report (instrument_id, statement, period_end DESC);
COMMENT ON TABLE financial_report IS '财报期次。年报与四季报期末可能同为一天（实测英伟达 2026/FY 与 2026/Q4 都是 01-24），所以唯一键带上期别文本';
COMMENT ON COLUMN financial_report.fiscal_year IS '券商给的财年，可能领先自然年（实测 2026-07-25 那期财年为 2027）；取最近一期请按 period_end 排序';

CREATE TABLE financial_item (
    report_id   bigint         NOT NULL REFERENCES financial_report (id) ON DELETE CASCADE,
    field_id    bigint         NOT NULL,
    field_name  varchar(64),                              -- 券商给的展示名，随语言变，仅作参考
    value       numeric(28,6),
    yoy         numeric(18,4),
    qoq         numeric(18,4),
    PRIMARY KEY (report_id, field_id)
);
COMMENT ON TABLE financial_item IS '财报数据项长表：券商字段随行业与版本变化，用长表避免改结构';
COMMENT ON COLUMN financial_item.field_id IS '券商字段编号，含义随报表类型不同（8001 在利润表是总收入、在资产负债表是资产合计），不要跨报表复用';

ALTER TABLE instrument
    ADD COLUMN profile             jsonb,
    ADD COLUMN profile_fetched_at  timestamptz;
COMMENT ON COLUMN instrument.profile IS '公司简介，券商给的中文名值对原样存（公司名称、CEO、员工数量、ISIN 代码、年结日、网址等）';
