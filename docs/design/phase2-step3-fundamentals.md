# 第 2 期·步骤 3：基本面数据

> 状态：**待认可**。目标是给选股与 AI 分析提供估值与财务底座，跟日 K 线一样先把"取得全、对得上、跑得稳"做实。

## 1. 要解决的问题

现在库里只有价格。判断"贵不贵、赚不赚钱、值不值得进池"还缺两类数据：

- **估值快照**：市值、市盈率（静态 / TTM）、市净率、股息率、每股收益、流通股本、换手率。逐日变化，跟着价格走。
- **财务报表**：营收、净利、资产负债、现金流的季度与年度序列。逐季变化，是同比环比与成长性的来源。

两类数据的更新频率、取数成本、失效方式都不同，**分开存、分开更**，不要塞进一张表。

## 2. 富途接口能力（已用 javap 查过真实签名，非文档推断）

| 接口 | 用途 | 一次取多少 | 限频 | 额度 |
| --- | --- | --- | --- | --- |
| `getSecuritySnapshot` | 估值快照 | **一次最多 400 只** | 30 秒 60 次 | 不占订阅、不占历史 K 线额度 |
| `getFinancialsStatements` | 财务报表 | **一次 1 只**，分页 1~50 条 | 30 秒 30 次 | 同上，不占额度 |
| `getCompanyProfile` | 公司简介 | 1 只 | 待实测 | 同上 |

快照返回的估值子结构 `EquitySnapshotExData` 实测有这些字段：

```
issuedShares 总股本      issuedMarketVal 总市值      outstandingShares 流通股本
outstandingMarketVal 流通市值   netAsset 净资产      netProfit 净利润
earningsPershare 每股收益  netAssetPershare 每股净资产
peRate 市盈率(静态)   peTTMRate 市盈率(TTM)   pbRate 市净率   eyRate 收益率
dividendTTM 股息TTM   dividendRatioTTM 股息率TTM   dividendLFY 上年度股息   dividendLFYRatio 上年度股息率
```

基础子结构另有 `turnoverRate` 换手率、`isSuspend` 停牌、`listTime` 上市日期。

财务报表按 `FinancialStatementsType` 分四类：`Income` 利润表、`BalanceSheet` 资产负债表、`CashFlow` 现金流量表、
`MainIndex` 主要指标；按 `F10Type` 分周期：`Annual` 年报、`Quarterly` 季报、`QuarterlyAnnual`（默认，季+年混合）。
返回是"字段字典 + 数据项"两段式：`structureList` 给 `fieldId → displayName`（当前语言，如"营业收入"），
`reportList` 每期一条，`itemList` 里每项带 `fieldId / data / yoy / qoq`，另有 `periodText`（如 `2024/Q3`）、
`currencyCode`、`accountingStandards`、`auditorReport`。

**关键取舍**：富途不保证 `fieldId` 跨市场跨版本稳定，且字段随行业不同而不同。所以**不建表列，存字段字典 + 长表**，
应用层再按需要投影出常用指标。这样新增字段不用改表结构。

## 3. 覆盖范围与更新节奏

沿用日 K 线已验证的分层：全量便宜的天天做，昂贵的只给池与持仓。

| 数据 | 范围 | 频率 | 成本估算 |
| --- | --- | --- | --- |
| 估值快照 | **全量 518 + 池/持仓 = 520 只** | 每交易日收盘后一次 | 520 / 400 = **2 次调用**，几秒 |
| 财务报表 | **池 + 持仓 20 只**，四类报表 | 每周一次 + 财报季触发 | 20 × 4 = 80 次调用，限频 30/30s → **约 80 秒** |
| 公司简介 | 池 + 持仓 20 只 | 加入池时一次，此后每月 | 20 次调用 |

全量做财务报表的话是 518 × 4 = 2072 次调用、约 35 分钟，且绝大多数标的我们并不看，**不做**。
将来若要扩展到全量，用 `stockFilter` 条件选股批量拿指标更划算，留作后续。

## 4. 存储设计（迁移 V5）

```sql
-- 估值快照：一只一天一行，可重跑覆盖
CREATE TABLE valuation_snapshot (
    instrument_id       bigint      NOT NULL REFERENCES instrument(id),
    trade_date          date        NOT NULL,
    market_cap          numeric(20,2),   -- 总市值
    float_market_cap    numeric(20,2),   -- 流通市值
    issued_shares       bigint,
    outstanding_shares  bigint,
    pe                  numeric(14,4),   -- 静态市盈率
    pe_ttm              numeric(14,4),
    pb                  numeric(14,4),
    eps                 numeric(14,4),
    net_asset_per_share numeric(14,4),
    net_asset           numeric(20,2),
    net_profit          numeric(20,2),
    dividend_ttm        numeric(14,4),
    dividend_yield_ttm  numeric(10,4),
    turnover_rate       numeric(10,4),
    suspended           boolean     NOT NULL DEFAULT false,
    fetched_at          timestamptz NOT NULL,
    PRIMARY KEY (instrument_id, trade_date)
);

-- 财报期次：一只一个报表类型一个报告期一行
CREATE TABLE financial_report (
    id            bigserial PRIMARY KEY,
    instrument_id bigint      NOT NULL REFERENCES instrument(id),
    statement     text        NOT NULL,   -- INCOME / BALANCE_SHEET / CASH_FLOW / MAIN_INDEX
    period_end    date        NOT NULL,   -- 财报截止日
    fiscal_year   int,
    period_text   text,                   -- 2024/Q3、2024/FY
    period_type   text,                   -- Q1..Q4 / ANNUAL
    currency      text,
    accounting_standards text,
    auditor_report       text,
    fetched_at    timestamptz NOT NULL,
    UNIQUE (instrument_id, statement, period_end)
);

-- 财报数据项：长表，不随富途加字段而改结构
CREATE TABLE financial_item (
    report_id  bigint  NOT NULL REFERENCES financial_report(id) ON DELETE CASCADE,
    field_id   bigint  NOT NULL,
    field_name text,                      -- 富途给的展示名，随语言变，仅作参考
    value      numeric(24,6),
    yoy        numeric(14,4),
    qoq        numeric(14,4),
    PRIMARY KEY (report_id, field_id)
);

CREATE INDEX idx_valuation_date ON valuation_snapshot (trade_date);
CREATE INDEX idx_report_instrument ON financial_report (instrument_id, statement, period_end DESC);
```

`instrument` 表加两列（同一迁移里 ALTER）：`profile_json jsonb`、`profile_fetched_at timestamptz`，
公司简介字段富途给得零散，直接存原样，不单独建表。

## 5. 作业与调度

新增两个作业，接进现有 `JobService`（单线程串行，`job_run` 留痕），命名沿用现有风格：

| 作业 | 触发 | 做什么 |
| --- | --- | --- |
| `VALUATION_SNAPSHOT` | 每交易日 17:40 ET（增量之后 10 分钟）；也可手动 | 全量 520 只分 2 批取快照，按当日交易日写入 |
| `FINANCIALS_REFRESH` | 每周六 07:00 ET；池成员变动时对新成员单独触发；也可手动 | 池 + 持仓 20 只 × 4 类报表，取最近 12 期，按期次 upsert |

排在日线增量之后，避开轮转窗口，两者都不占订阅与历史 K 线额度，**不会影响已跑稳的日 K 线链路**。
调度同样受 `trader.marketdata.schedule-enabled` 控制：开发默认关、生产默认开。

## 6. 接口

| 方法与路径 | 说明 |
| --- | --- |
| `GET /api/fundamentals/{symbol}` | 一只的最新估值 + 最近 N 期主要指标 |
| `GET /api/fundamentals/{symbol}/valuation?from&to` | 估值时间序列 |
| `GET /api/fundamentals/{symbol}/reports?statement=&limit=` | 财报期次与数据项 |
| `GET /api/fundamentals/coverage` | 覆盖情况：多少只有当日估值、多少只有财报、最近期次 |
| `POST /api/fundamentals/valuation/refresh` | 手动跑估值快照作业 |
| `POST /api/fundamentals/financials/refresh?all=false` | 手动跑财报作业 |

按项目约定，同步更新 `docs/API.md`、`docs/postman/build_collection.py`、前端 `trader-web/src/api/`。

## 7. 审计

`GET /api/bars/audit` 只管日线，另加 `GET /api/fundamentals/audit`，纳入 `check-daily.sh` 一起跑：

- **完整性（关键）**：当日全量 520 只都有估值快照。
- **合理性（关键）**：市值 > 0；市盈率为负或空时不算错（亏损股正常），但要给出计数；停牌股允许缺值。
- **陈旧度**：池与持仓的财报最近期次距今不超过 6 个月（超了通常是财报季没跟上）。
- **作业**：最近一次估值作业 OK。

## 8. 分步交付

1. 网关层：`MarketDataGateway` 加 `snapshots(List<Instrument>)`、`financials(Instrument, statement, periods)`、
   `companyProfile(Instrument)`；富途侧走已有的 `qotCall` + 限流器，新增三个限流名
   （`get-security-snapshot 60/30s`、`get-financials 30/30s`、`get-company-profile 30/30s`，先按文档配，实测再收敛）。
2. 存储层：V5 迁移 + 三个仓储。
3. 核心层：两个作业 + 覆盖视图 + 审计。
4. 接口与前端：REST + Postman + 「基本面」页（估值表 + 财报趋势）。
5. 生产验证：跟日 K 线一样，先手动跑一轮，再挂调度观察一个财报周。

每步都能独立验证，跟日 K 线的节奏一致。

## 9. 待实测确认的点

- `getCompanyProfile` 的限频文档没写，先按 30/30s 配，实测后收敛。
- 美股财报 `fieldId` 的稳定性：先对 20 只取一轮，比对两次结果的字段集合是否一致。
- `MainIndex`（主要指标）与另外三张表的字段是否重叠，决定要不要四类全存。
- 快照在盘中与盘后取到的估值是否不同（市值随价格变），决定作业时点是否必须在收盘后。
