-- 第 3 期·步骤 2：账户与持仓的每日快照、对账。实测依据见 docs/ARCHITECTURE.md §16.1。
-- 账户号不落明文：account_key 是带密钥的 HMAC（密钥在外置配置）。纯哈希不行——
-- 盈透账户号只有约 10^8 种取值，几秒就能穷举回去。

ALTER TABLE instrument ADD COLUMN ibkr_con_id bigint;
CREATE UNIQUE INDEX instrument_ibkr_con_id_uq ON instrument (ibkr_con_id) WHERE ibkr_con_id IS NOT NULL;
COMMENT ON COLUMN instrument.ibkr_con_id IS '盈透合约 conId；持仓映射优先按它找标的，首次按代码（空格→点）匹配后写入';

-- 步骤 3（持仓自动维护池）要用的两列，与本步一起加，免得同一期两次改表
ALTER TABLE pool_member ADD COLUMN source varchar(8) NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL', 'IBKR'));
ALTER TABLE pool_member ADD COLUMN return_role varchar(12) CHECK (return_role IN ('POOL'));
COMMENT ON COLUMN pool_member.source IS 'MANUAL 手工加入；IBKR 由盈透持仓自动加入（清仓后移出池）';
COMMENT ON COLUMN pool_member.return_role IS '被持仓升级为 HOLDING 之前的角色；清仓后回到它（目前只会是 POOL）';

CREATE TABLE account_snapshot (
    id                   bigserial     PRIMARY KEY,
    broker               varchar(8)    NOT NULL CHECK (broker IN ('IBKR')),
    account_key          varchar(32)   NOT NULL,          -- HMAC-SHA256(密钥, 券商:账户号) 前 32 位十六进制
    account_mask         varchar(16)   NOT NULL,          -- 展示用脱敏形式
    as_of_date           date          NOT NULL,          -- 快照所属交易日（收盘口径）
    taken_at             timestamptz   NOT NULL,
    currency             varchar(8),
    net_liquidation      numeric(20,4),
    total_cash           numeric(20,4),
    stock_market_value   numeric(20,4),                   -- 盈透口径的股票市值（对账的另一边）
    gross_position_value numeric(20,4),
    available_funds      numeric(20,4),
    buying_power         numeric(20,4),
    excess_liquidity     numeric(20,4),
    unrealized_pnl       numeric(20,4),
    realized_pnl         numeric(20,4),
    accrued_dividend     numeric(20,4),
    position_value       numeric(20,4),                   -- 本系统口径：Σ 数量 × 收盘价（只算股票）
    positions            integer       NOT NULL,
    recon_status         varchar(8)    NOT NULL CHECK (recon_status IN ('OK', 'WARN', 'FAIL')),
    recon                jsonb         NOT NULL,          -- 各对账项 [{name, status, detail}]
    raw                  jsonb         NOT NULL,          -- 券商原始标签（已剔除账户号）
    job_run_id           bigint,
    UNIQUE (broker, account_key, as_of_date)
);
CREATE INDEX account_snapshot_date_idx ON account_snapshot (as_of_date);
COMMENT ON TABLE account_snapshot IS '账户资金快照，一个账户一天一行，同一天重拍覆盖；券商只给当前状态，漏掉的日子补不回来';

CREATE TABLE position_snapshot (
    snapshot_id    bigint        NOT NULL REFERENCES account_snapshot (id) ON DELETE CASCADE,
    broker_ref     varchar(32)   NOT NULL,                -- 盈透 conId
    symbol         varchar(32)   NOT NULL,                -- 券商原样写法（类别股带空格）
    instrument_id  bigint        REFERENCES instrument (id),
    security_type  varchar(8),
    currency       varchar(8),
    exchange       varchar(16),
    quantity       numeric(20,6) NOT NULL,
    average_cost   numeric(20,6),
    price          numeric(18,6),
    price_source   varchar(8)    NOT NULL CHECK (price_source IN ('BAR', 'SNAPSHOT', 'NONE')),
    market_value   numeric(20,4),
    cost_basis     numeric(20,4),
    unrealized_pnl numeric(20,4),
    cash_equivalent boolean      NOT NULL DEFAULT false,  -- 用户指定的现金管理工具：计入市值，不进池、不参与持仓集合核对
    PRIMARY KEY (snapshot_id, broker_ref)
);
CREATE INDEX position_snapshot_instrument_idx ON position_snapshot (instrument_id);
COMMENT ON COLUMN position_snapshot.price_source IS 'BAR 当日 K 线收盘；SNAPSHOT 富途快照价（库里没有当日 K 线时兜底）；NONE 缺价';
