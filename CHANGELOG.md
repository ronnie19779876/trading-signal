# CHANGELOG

## 1.0.0-SNAPSHOT（开发中）

### 第 2 期·步骤 2：实时报价订阅，不落库（2026-09-03）

- 领域 `Quote` / `MarketSession` / `SubscriptionInfo`；`MarketDataGateway` 增加 subscribeQuotes / unsubscribeQuotes / subscriptionInfo / addQuoteListener（富途 Basic 推送 → dispatch 线程 → 监听器）。
- 有效价按时段取（实测盘前 curPrice 冻结、preMarket 更新）；时段由心跳的 marketUS 判定，拿不到按美东时钟。
- `QuoteSubscriptionService` 对账（池 ∪ 持仓；新增/延后反订阅；连上/重连/池变动/手工触发；暂停恢复）；与全量轮转的额度协调（默认轮转期间暂停）。
- `QuoteCache` + `QuoteStreamService`（SSE 每秒合并帧、15 秒状态）；`/api/quotes*`；前端实时报价表（EventSource）与 lightweight-charts 日 K 图。
- 配置 `trader.marketdata.realtime.*`（开发机 auto-subscribe=false，发布包 true）；Postman 新增「实时报价」目录。
- 测试：单元 87 个；集成 `FutuQuotesIT`。
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
