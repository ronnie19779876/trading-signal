# 第 2 期 · 步骤 1 设计：日 K 线行情底座

> 状态：**已认可并实现**（2026-09-03）。实现要点与实测结论并入 docs/ARCHITECTURE.md §11，本文保留为设计记录。
> 不含主机名、IP、账户号、网关端口。所有富途接口行为均已对真实 OpenD 实测（只读）。

## 1. 实测事实（决定方案的部分）

| 事实 | 对设计的影响 |
| --- | --- |
| 富途美股板块（347 个）里**没有**标普 500 / 纳指 100 的完整成分股，只有「标普500指数十大成分股」 | 成分股必须另找来源（§3） |
| 历史 K 线额度：本账号 **7 天滚动 100 只**（当前已用 56、剩 44）；同一只 7 天内重复请求不重复计，不同周期只算 1 次；每 30 秒最多 60 次首页请求，续页不限 | 600 只全量不能靠 `requestHistoryKL`；它只留给需要 20 年深度的少数标的 |
| `sub(KL_Day)` 后 `getKL(reqNum=1000)` 实测返回 **1000 根日 K（2022-09-08 ～ 2026-09-02，约 4 年）**，不消耗历史额度，只占订阅额度（本账号 100，订阅满 1 分钟才能反订阅） | 全量标的用「订阅 → getKL → 反订阅」轮转拉取，零额度成本 |
| 日 K 字段：time(yyyy-MM-dd 00:00:00)、OHLC、前收、成交量、成交额、换手率、PE、涨跌幅、isBlank | 表结构按此设计 |
| `requestRehab` 给出每个除权除息日的 fwd/bwd 因子 A/B、分红、拆股等；**前复权价 = 不复权价 × fwdFactorA + fwdFactorB**，因子对"该除权日之前"的价格生效且已累计；与富途自身前复权序列相对误差 ≈ 1e-5（因子只有 5 位小数）；成交量不复权。限频 60/30s | 存不复权 + 因子，读取时复权（§4、§5） |
| 交易日历 `requestTradeDate(TradeDateMarket_US=2)` 可用，限频 30/30s | 缺口检测按交易日 |
| `getStaticInfo`：名称、lotSize、secType（3 股票 / 4 ETF）、上市日、退市标志、交易所 | 标的表字段 |
| Wikipedia「List of S&P 500 companies」表可解析：503 行，含 Symbol / 名称 / GICS 行业 / 子行业 / 加入日期 / CIK；纳指 100 的成分股在单独页面「List of NASDAQ-100 companies」，同样可解析（102 行，Ticker / 公司 / ICB 行业 / 子行业——注意分类体系是 ICB 不是 GICS）。SSGA 的 SPY 每日持仓 xlsx 可直接下载（含 BRK.B 等写法与富途一致），可做交叉核对。Invesco QQQ 持仓与 slickcharts 拒绝脚本访问 | 成分股来源：Wikipedia 主，SPY 交叉核对，CSV 导入兜底 |

## 2. 范围

| 做 | 不做（后续步骤） |
| --- | --- |
| 标的表与三种角色：全量（UNIVERSE，标普 500 ∪ 纳指 100）、标的池（POOL，上限 50，手工维护）、持仓（HOLDING，本步骤手工加，第 3 期由盈透持仓自动维护） | 分钟 K 线、盘前盘后 |
| 成分股同步（Wikipedia 解析、SPY 交叉核对、CSV 导入）与成员变更历史 | 实时行情订阅推送（步骤 2） |
| 全量标的日 K：1000 根（≈4 年）轮转拉取 + 每日增量 | 基本面（步骤 3） |
| 池与持仓标的：20 年深度历史（历史额度）+ 每日增量 | 盈透历史数据（保留为备选来源，不实现） |
| 复权因子、交易日历、不复权 / 前复权 / 后复权读取 | 技术指标计算 |
| 覆盖统计、额度视图、跑批（定时 + 手工）、作业记录、前端「行情」页（表格） | K 线图表（后续） |

## 3. 标的与成分股

- **成分股来源**：`UniverseSource` 接口，实现 `WikipediaUniverseSource`（「List of S&P 500 companies」与「List of NASDAQ-100 companies」两页各一张 `id=constituents` 的表，按表头 Symbol/Ticker 定位，解析代码、名称、行业与子行业（标普是 GICS、纳指是 ICB，原样存并记录体系）、加入日期）、`SpyHoldingsCrossCheck`（下载 SSGA xlsx，只用于比对并报告差异，不作数据源）、`CsvUniverseSource`（`POST /api/universe/import` 上传 `index_code,symbol` 兜底）。
- 代码规范化：Wikipedia 用 `BRK.B`，富途也用 `BRK.B`（实测板块列表里就是这个写法）；统一大写；同步后用 `getStaticInfo` 校验富途认得该代码并取名称 / 类型 / 上市日，认不得的标记 `UNRESOLVED` 并在页面列出。
- **成员变更历史**：`index_constituent(index_code, instrument_id, since, until)`，同步时做集合差：新增写 `since=今天`，消失的把 `until=今天`。退出指数的标的保留在 `instrument` 表与已有 K 线（永不删行）。
- 同步节奏：每周六 06:30 ET 自动 + 手工触发；两个来源都失败时保留旧成员并告警。

## 4. 数据模型（Flyway V3）

```sql
instrument          id bigserial PK, market, symbol, name, sec_type STOCK|ETF|OTHER, lot_size, list_date, delisted bool,
                    exchange, resolve_status RESOLVED|UNRESOLVED, created_at, updated_at；UNIQUE(market, symbol)
index_constituent   index_code SP500|NDX100, instrument_id, sector, sub_industry, classification GICS|ICB, since date, until date NULL, source；PK(index_code, instrument_id, since)
pool_member         instrument_id PK, role POOL|HOLDING, note, added_at
daily_bar           instrument_id, trade_date, open, high, low, close, last_close, volume bigint, turnover, turnover_rate,
                    change_rate, pe, source FUTU_KL|FUTU_HIST, fetched_at；PK(instrument_id, trade_date)   -- 一律不复权
rehab_factor        instrument_id, ex_date, fwd_a, fwd_b, bwd_a, bwd_b, company_act_flag, dividend, sp_dividend,
                    split_base, split_ert, fetched_at；PK(instrument_id, ex_date)
trading_day         market, trade_date, kind；PK(market, trade_date)
bar_sync_state      instrument_id PK, depth KL1000|HIST20Y, earliest_date, latest_date, bar_count, last_success_at, last_error, hist_quota_used_at
job_run             id bigserial PK, job, trigger MANUAL|SCHEDULE, started_at, finished_at, status RUNNING|OK|PARTIAL|FAILED, summary text
```

- 价格 `numeric(18,6)`，成交额 `numeric(20,4)`，时间 `timestamptz`（UTC）。
- `daily_bar` 幂等 upsert（`ON CONFLICT DO UPDATE`）：增量重写最近几根，天然纠正盘后修正。
- `source` 记录这根来自哪条通道，两条通道价格实测一致（都是富途不复权），字段留作审计。

## 5. 采集流程（trader-core）

**全量轮转（UniverseRefresh）**：把全量标的按批（默认 90 只，留 10 个订阅额度给别的用途）→ `sub(KL_Day, 不注册推送)` → 逐只 `getKL(reqNum)`（全量首拉 1000，增量 10）→ upsert → 批内停留满 65 秒后 `unsub` → 下一批。600 只 ≈ 7 批 ≈ 8 分钟。任一只失败记录到 `bar_sync_state.last_error`，不中断整批。

**深度回补（DeepBackfill）**：只对 POOL / HOLDING：先 `requestHistoryKLQuota`，剩余额度 ≤ 保留值（默认 10）则进入等待队列（页面显示"待额度"），否则 `requestHistoryKL(None, Day, 2006-01-01 起, 分页 nextReqKey)` → upsert → `requestRehab` → 状态 `HIST20Y`。加入池时自动触发；每周 ≤ 额度余量。

**每日增量（DailyIncrement，ET 17:30，周一到周五）**：先 `requestTradeDate` 更新交易日历；对 POOL / HOLDING（以及可配置的全量）取 `latest_date` 之后缺的交易日数，走轮转通道 `getKL(缺口 + 5)` upsert；周一同时刷新复权因子。判定"当天那根已收盘"用美东 16:00 之后 + 富途 `isBlank=false`。

**复权读取（AdjustedBars）**：`adjust=none|forward|backward`；forward：对每根 bar 找 `ex_date > trade_date` 的最早因子记录，价 × A + B；backward 同理用 bwd。成交量不变。

**限频与额度**：全部富途调用经第 1 期的 `FutuRateLimits`，新增 `sub 30/30s`、`get-kl 60/30s`、`request-history-kl 60/30s`、`request-rehab 60/30s`、`request-trade-date 30/30s`、`get-static-info 30/30s`（保守值，可配置）。单实例纪律：同一时刻只允许一个实例开调度。

## 6. 网关端口（trader-gateway-api 新增 `MarketDataGateway`，富途实现）

```java
CompletableFuture<List<InstrumentStatic>> staticInfo(List<Instrument> instruments);       // ≤200 只/次
CompletableFuture<HistoryQuota> historyQuota();                                            // used / remain / 明细
CompletableFuture<List<DailyBar>> historyDailyBars(Instrument i, LocalDate from, LocalDate to);   // 分页在适配器内完成；占额度
CompletableFuture<Void> subscribeDailyBars(List<Instrument> instruments);
CompletableFuture<Void> unsubscribeDailyBars(List<Instrument> instruments);
CompletableFuture<List<DailyBar>> recentDailyBars(Instrument i, int count);                // 需已订阅；≤1000
CompletableFuture<List<RehabFactor>> rehab(Instrument i);
CompletableFuture<List<TradingDay>> tradingDays(Market market, LocalDate from, LocalDate to);
```

领域类型（trader-domain）：`DailyBar`、`RehabFactor`、`TradingDay`、`InstrumentStatic`、`HistoryQuota`、`IndexCode`、`PoolRole`。富途 SPI 里把 `onReply_Sub / GetKL / RequestHistoryKL / RequestRehab / RequestTradeDate / GetStaticInfo / RequestHistoryKLQuota` 交给第 1 期的 `FutuReplyRegistry`。

## 7. REST 与前端

| 接口 | 说明 |
| --- | --- |
| `POST /api/universe/sync` / `GET /api/universe?index=SP500|NDX100&role=` / `GET /api/universe/{symbol}` / `POST /api/universe/import`（CSV） | 成分股同步、查询、导入 |
| `GET /api/pool` / `POST /api/pool/{symbol}?role=POOL|HOLDING` / `DELETE /api/pool/{symbol}` | 标的池维护（上限 50） |
| `POST /api/bars/refresh/universe?depth=1000` / `POST /api/bars/backfill/{symbol}` / `POST /api/bars/increment` | 跑批手工触发（异步，返回 job id） |
| `GET /api/bars/{symbol}?from&to&adjust=none|forward|backward` | K 线查询 |
| `GET /api/bars/coverage` / `GET /api/bars/quota` / `GET /api/jobs?limit=` | 覆盖统计（行数、标的数、最早/最新交易日、缺口）、历史额度、作业记录 |

前端新增「行情」页：覆盖统计与额度卡片、三个跑批按钮与最近作业、标的池表格（增删）、按标的查看 K 线表格（复权口径切换）。Postman 集合同步新增。

## 8. 配置（`trader.marketdata.*`，机制参数在 jar 内，开关在外置配置）

```yaml
trader:
  marketdata:
    universe:
      wikipedia-sp500-url / wikipedia-ndx100-url / spy-holdings-url   # 公开 URL，可配置
      cross-check-spy: true
      sync-cron: "0 30 6 * * SAT"
    pool:
      max-size: 50
    refresh:
      batch-size: 90            # 每批订阅只数（≤ 订阅额度 - 预留）
      hold-seconds: 65          # 满 1 分钟才能反订阅
      universe-increment: true  # 全量标的是否每日增量
    history:
      from: 2006-01-01
      quota-reserve: 10
    schedule-enabled: false     # 发布包 true / 开发机 false；单实例
    increment-cron: "0 30 17 * * MON-FRI"
    zone: America/New_York
```

## 9. 测试与验收

- 单元：Wikipedia 解析（固定 HTML 样本）、CSV 导入、成员差集、复权计算（用实测的 AAPL 2026-08-10 除息前后数据做已知答案）、轮转分批与停留、额度守卫、增量缺口计算。
- 集成（真实 OpenD，只读，增加 2 只标的的订阅一分钟）：staticInfo、sub+getKL、requestHistoryKL 一页、rehab、tradeDates、quota；Wikipedia 与 SPY 下载。
- 验收：成分股同步得到 ≈600 只（含差异报告）；全量轮转 8 分钟内完成，`daily_bar` ≈ 60 万行；池内 2 只深度回补到 2006 年；`GET /api/bars/AAPL?adjust=forward` 与富途前复权序列相对误差 < 1e-4；增量在收盘后补上当日 K 线且幂等。

## 10. 待拍板

1. 全量标的深度：**1000 根（≈4 年）走订阅通道**（建议，零额度、8 分钟）；备选 A 用历史额度排队拉 20 年（每周 ≤ 40 只，约 15 周）；备选 B 用盈透拉 20 年（两套来源，成交量口径不同）。
2. 全量标的**每日增量默认开启**（建议；成本约 8 分钟/日的订阅轮转），还是只增量池内 50 只。
3. 成分股来源：**Wikipedia 主 + SPY 持仓交叉核对 + CSV 导入兜底**（建议）。
4. 深度历史起点 2006-01-01（20 年）。
5. 标的池上限 50；HOLDING 角色本步骤手工加，第 3 期改为盈透持仓自动维护。
6. **存不复权 + 因子、读取时复权**（建议），而不是存前复权（前复权每逢除权全量重写）。
7. 前端本步骤只做表格与统计，K 线图表放到步骤 2 一起。
