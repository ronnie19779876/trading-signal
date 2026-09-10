# CHANGELOG

## 1.1.0-SNAPSHOT

### 第 2 期·步骤 5：定时作业可靠性（进行中）

- 调度碰撞不再静默丢作业：每 5 分钟重试、最多 6 次；彻底放弃时写 `job_run` 的 `SKIPPED` 留痕（V8）。
  此前增量与估值只隔 10 分钟而增量实测要 6.5 分钟，一旦超时估值被直接丢弃，而估值是时点数据当天不补就永远没有。
- 新增当天补偿检查（美东 21:00，早于次日盘前）：缺 K 线或估值就补跑，触发方式记 `CATCHUP`。
- 新增 `jobs` 健康指标：任一定时作业 FAILED / SKIPPED / 逾期未跑 → DEGRADED。
- `scripts/check-daily.sh` 合并三段：日线审计 + 基本面审计 + 运行健康。此前只调日线审计，
  恰好漏掉唯一能发现估值缺失的检查。

### 第 2 期·步骤 4：基准标的与交易日历（开发中）

- 新增 `BENCHMARK` 角色（V6/V7）：基准照常采集日 K、复权、实时订阅，但不参与选股（`UniverseScope.candidates()` 排除）。
- 交易日历回补作业 `CALENDAR_BACKFILL`：券商段（实测只能回到 2016-09）+ 更早的从池/持仓/基准的日 K 线反推，
  `trading_day.source` 区分来源；`GET /api/bars/calendar`、`POST /api/bars/calendar/backfill`、覆盖视图与审计项。
- 审计新增 `historyGaps`（最近 90 天对照日历的缺口，提示项）与 `GET /api/bars/gaps`（全历史深扫）。
  实测发现富途的 SPY 缺 26 个交易日（2009~2012）而前收连续性检查查不出来——券商自己的前收与缺口自洽。
- 反推要求一天≥2 只标的同时成交：实测富途给 SPY 在三个美股假日留了脏 K 线，只取并集会把假日算成交易日。

### 第 2 期·步骤 3：基本面数据（进行中）

- 第 1 步网关层：`MarketDataGateway` 增加 `snapshots` / `financials` / `companyProfile`（富途快照、四类财务报表、公司简介），
  新增三个限流名与 `FutuFundamentals` 映射器；领域新增 `ValuationSnapshot` / `FinancialReport` / `FinancialStatement` / `CompanyProfile`。
- 存储层 V5：`valuation_snapshot`、`financial_report`（唯一键带期别）、`financial_item`（长表）、`instrument.profile`。
- 核心层：`VALUATION_SNAPSHOT`（全量每交易日，一次 400 只）与 `FINANCIALS_REFRESH`（池与持仓每周，四类报表）两个作业、
  基本面审计、查询服务；`/api/fundamentals/*` 七个接口、Postman 与前端 API 同步。
- `POST /api/fundamentals/financials/refresh?all=true` 支持全量成分股回补（518 只约 41 分钟，不取公司简介）。
- 修正：富途把美股 REITs 也归为 Trust（标普 500 里 25 只），按"ETF 没财报"跳过会漏掉它们；改为不按类型过滤，
  审计也区分"有财报但过旧"与"从来没有财报（基金正常）"。
- 报价新增 `referenceClose`（涨跌基准）：常规时段为券商昨收，盘前盘后夜盘为上一个常规时段收盘。
  此前前端把券商 `lastClose` 当昨收显示，盘前时它比真实基准早一个交易日，读者自算的涨跌幅与显示值对不上。
- 修：`valuation-cron` / `financials-cron` 只写在配置记录的 `@DefaultValue` 上、没进 jar 内 `application.yml`，
  开发实例调度关闭发现不了，生产（调度开启）启动即失败。补齐 yaml，并加 `ScheduledPlaceholdersTest` 守住这条。
- 前端「基本面」页：估值概览与序列、四类财务报表（字段按券商返回动态成列）、公司简介、作业触发。
- 开发实例对真实网关跑通：520 只估值零失败、19 只个股 889 期财报零失败、20 只公司简介，审计通过。
- 对真实 OpenD 实测确认取值口径：估值字段是 proto required（判空无效）、亏损股市盈率为负、
  美股 ETF 净值多数缺失以 0 占位、四类报表字段编号不跨表通用；并修正了设计里年报与四季报期末同日导致的唯一键冲突。

- 审计接口识别休市日：显式传入非交易日时回"当天休市"并判通过，不再误报 520 只缺 K 线（日历覆盖不到的旧日期照常审计）。
- 盈透同一错误码持续复现时日志降频：首次照常告警，之后每 10 分钟汇总一条并带上被压掉的次数，连上后复位。一次 25 小时的网关停机曾刷出 1500 多行同样的 502。

## 1.0.0-SNAPSHOT（开发中）

### 第 2 期·步骤 2：实时报价订阅，不落库（2026-09-03）

- 领域 `Quote` / `MarketSession` / `SubscriptionInfo`；`MarketDataGateway` 增加 subscribeQuotes / unsubscribeQuotes / subscriptionInfo / addQuoteListener（富途 Basic 推送 → dispatch 线程 → 监听器）。
- 有效价按时段取（实测盘前 curPrice 冻结、preMarket 更新）；时段由心跳的 marketUS 判定，拿不到按美东时钟。
- `QuoteSubscriptionService` 对账（池 ∪ 持仓；新增/延后反订阅；连上/重连/池变动/手工触发；暂停恢复）；与全量轮转的额度协调（默认轮转期间暂停）。
- `QuoteCache` + `QuoteStreamService`（SSE 每秒合并帧、15 秒状态）；`/api/quotes*`；前端实时报价表（EventSource）与 lightweight-charts 日 K 图。
- 配置 `trader.marketdata.realtime.*`（开发机 auto-subscribe=false，发布包 true）；Postman 新增「实时报价」目录。
- 测试：单元 87 个；集成 `FutuQuotesIT`。
- `GET /api/bars/audit` 日线数据审计与 `scripts/check-daily.sh`（收盘后巡检）；生产部署到服务器用户目录（`~/trading-signal` 软链）并完成首轮装载。
- 加入标的池时库里没有的代码（ETF、非成分股 ADR）先向富途解析静态信息并自动建档（SPY、TSM）。
- 收尾（数据质量核查后）：V4 `bar_sync_state.rehab_fetched_at`；`REHAB_REFRESH` 作业（`POST /api/bars/rehab/refresh`）；每日增量按 7 天到期刷新全量复权因子；覆盖统计增加 `rehabCovered`。

### 第 2 期·步骤 1：日 K 线行情底座（2026-09-03）

- 领域与端口：DailyBar / RehabFactor / TradingDay / InstrumentStatic / HistoryQuota / IndexCode / PoolRole / Adjustment；`MarketDataGateway`（富途实现，含分页历史、订阅取 K、复权因子、交易日历、额度）。
- 存储 V3：instrument、index_constituent（since/until）、pool_member、daily_bar（不复权）、rehab_factor、trading_day、bar_sync_state、job_run。
- 成分股：Wikipedia 标普 500 + 纳指 100 解析、SPY 持仓 xlsx 交叉核对、CSV 导入；富途静态信息解析（brokerId=0 判未解析）。
- K 线：全量订阅轮转（零历史额度）、池/持仓 20 年深度回补（额度守卫）、每日增量（交易日历缺口 + overlap）、读取层复权（实测定为逐事件复合 PER_EVENT）。
- 作业：单线程串行 `JobService` + job_run 记录 + 进度 + 取消；定时增量与周六成分股同步（可开关）。
- 接口 `/api/universe*`、`/api/pool*`、`/api/bars*`、`/api/jobs*`；错误映射新增 404 / 409；前端「行情」页；Postman 集合新增「行情」目录。
- 测试：单元 78 个；集成 `FutuMarketDataIT`（含复权语义判定）。

### 第 1 期：网关接入层（2026-09-03）

- `trader-gateway-api`：`BrokerGateway` 增加生命周期、账户、监听器；`ReferenceDataGateway`；`GatewayStatus` 扩展心跳/重连/事实；与 SDK 无关的 `ConnectionSupervisor`（指数退避重连、心跳、过期结果丢弃）。
- 盈透：`IbkrConnection` / `IbkrWrapper` / `IbkrRequestRegistry`，令牌桶限速，`reqCurrentTime` 心跳，受管账户、合约查询（`reqContractDetails`），1101 数据丢失映射为 reconnected 事件。
- 富途：行情与交易两条 `FutuChannel` 各自受控，`FutuReplyRegistry`（seq → Future，早到回复暂存），`getGlobalState` 心跳与事实，`getAccList` 账户，按接口限频。
- 核心与接口：`GatewayLifecycle`（启动自动连接）、`GatewayEventRecorder` + V2 `gateway_event`、`GET/POST /api/gateways*`、健康指标 `gateways`（DEGRADED），统一错误响应。
- 前端：系统页每 5 秒刷新，网关状态 / 心跳 / 事实 / 账户（脱敏）/ 连接断开按钮 / 最近事件。
- 测试：61 个单元测试；集成测试 `IbkrGatewayIT`、`FutuGatewayIT`、`ReconnectIT`（本地 TCP 中继断线重连），对真实网关全部通过。

### 第 0 期：项目骨架（2026-09-03）

- Maven 多模块工程 `org.jdkxx.trader:trader`，`${revision}` + flatten 一处改版本，Maven Wrapper 3.9.16。
- 九个后端模块与依赖方向：`app → { core, gateway-ibkr, gateway-futu }`，`core → { gateway-api, storage, ai }`；三个供应商 SDK 各锁在一个模块。
- 富途 SDK 与盈透 SDK 的 protobuf 冲突：以 `sdk/futu-api-shaded`（protobuf 重定位）解决，`scripts/install-sdks.sh` 一键安装两个非 Central 构件。
- 配置分层：jar 内环境无关默认值；`config/`（开发）与 `deploy/config/`（生产）外置；敏感项只在 gitignore 文件；数据库密码可由 `~/.pgpass` 提供。
- 环境隔离：`trader.environment` 必填，`EnvironmentGuard` 在 Flyway 迁移后比对库内 `app_environment` 标记，只有全新空库才自动盖章。
- `GET /api/system/info`、`/actuator/health|info`，Vue 3 系统信息页（同源打进 jar）。
- 脚本：`run-local.sh`、`package.sh`、`check-secrets.sh`（可装成 pre-commit）、`install-git-hooks.sh`；部署模板 `deploy/bin/trader.sh` 与 systemd 单元。
