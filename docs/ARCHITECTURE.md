# 架构设计说明书

> 版本：1.3（第 0 期骨架 + 第 1 期网关接入层 + 第 2 期步骤 1 日 K 线 + 步骤 2 实时报价，2026-09-03）。各期设计均已经用户认可。
> 本文不含任何主机名、IP、账户号、网关端口——这些只存在于被 `.gitignore` 排除的外置配置里。

## 1. 目标与分期

基于 AI 大模型的美股量化交易系统，两家券商网关分工明确：

| 券商 | 职责 |
| --- | --- |
| 盈透 IBKR | 持仓账户：每日账户资金、收盘持仓价格与盈亏、交易下单 |
| 富途 Futu | 跟踪与分析：标普 500 + 纳指 100 全量日 K 线（标的池 50 只日常增量）、基本面数据（同样日常增量）、标的池与持仓标的的实时行情订阅（不落库）、入场信号判据与 AI 基本面分析 |

分期路线图（每期开工前单独出设计并经认可）：

| 期 | 内容 |
| --- | --- |
| 0 | **骨架**（已交付）：多模块、配置分层、环境守卫、健康页、前端骨架、脚本与部署模板 |
| 1 | **网关接入层**（已交付，§10）：连接管理 / 断线重连 / 健康探测、请求-回调关联、限频闸门、账户与合约查询、事件时间线 |
| 2 | 行情数据底座：**步骤 1 日 K 线（已交付，§11）**；**步骤 2 实时报价订阅，不落库（已交付，§12）**；步骤 3 基本面 |
| 3 | 账户与持仓：盈透账户资金、收盘持仓与盈亏的每日快照与对账 |
| 4 | 基本面与 AI 分析：富途基本面增量、入场信号判据、OpenAI 结构化分析、信号页 |
| 5 | 下单链路 + 风控（盈透）：先人工确认制，再逐步自动化 |

## 2. 模块与依赖方向

```
trading-signal/
├── trader-common          基础工具：AppEnvironment、MarketClock（America/New_York）、Masking。无 Spring、无 SDK
├── trader-domain          领域模型：Broker（含职责）、Market、Instrument。无 Spring、无 SDK
├── trader-gateway-api     网关端口：BrokerGateway / ReferenceDataGateway / GatewayListener、GatewayStatus；support.ConnectionSupervisor（与 SDK 无关的连接状态机）
├── trader-gateway-ibkr    盈透适配器：IbkrGateway / IbkrConnection / IbkrWrapper / IbkrRequestRegistry、mapper.*（com.ib.client.* 只在这里）
├── trader-gateway-futu    富途适配器：FutuGateway / FutuChannel / FutuReplyRegistry、mapper.*（com.futu.openapi.* 只在这里）
├── trader-storage         PostgreSQL：Flyway 迁移（V1 app_environment、V2 gateway_event、V3 行情八表）、EnvironmentGuard、pgpass 密码来源、仓储
├── trader-ai              OpenAI 接入：AiProperties、OpenAiClientFactory（com.openai.* 只在这里）
├── trader-core            业务编排：网关（GatewayRegistry / Lifecycle / EventRecorder / Service）、行情底座（marketdata.*：成分股同步、轮转拉取、深度回补、增量、复权读取、作业）、SystemInfoService
├── trader-app             Spring Boot 启动、REST、静态前端、fat jar；把两家适配器装配进核心
├── trader-web             Vue 3 + Vite + TypeScript + Element Plus（npm 工程，不是 Maven 模块）
└── sdk/                   tws-api 安装、futu-api-shaded 重定位工程
```

依赖方向（模块顺序即依赖顺序，不得反向）：

```
app → { core, gateway-ibkr, gateway-futu }
core → { gateway-api, storage, ai }
gateway-ibkr / gateway-futu → gateway-api → domain → common
storage → domain → common ；ai → common
```

设计要点：

- **端口与适配器分离**（六边形架构）：`trader-gateway-api` 定义端口，两家券商各一个适配器；`trader-core` 只认端口，适配器在 `trader-app` 层插入。将来想把某个网关拆成独立进程，只需换一个适配器实现。
- **供应商 SDK 隔离**：SDK 在各自模块里 `<optional>true</optional>`，编译期下游 import 不了；`trader-app` 另加 `runtime` 依赖只为打进 fat jar。
- 包名根 `org.jdkxx.trader`，各模块 `org.jdkxx.trader.<module>`。不用 Lombok（Java 21 record 足够），不用 JPA（JdbcTemplate + Flyway，SQL 可控）。

## 3. 第三方 SDK 供给与 protobuf 冲突

**冲突事实**（2026-09-03 实测）：富途 `futu-api` 的 protobuf 生成代码是 protoc 3.5.1 产物，跑在 protobuf-java 4.x 上初始化即 `NoSuchMethodError`；盈透 `tws-api 10.30` 内置 protobuf 生成类（gencode 4.29.5），要求 protobuf-java ≥ 4.29.5，且当前 IB Gateway 的 server version 已超过全部 protobuf 协议阈值，4.x 运行时不可省。两套 SDK**不能共用平铺 classpath**。

**解决方案（方案 A，已验证）**：`sdk/futu-api-shaded` 是一个独立小工程，用 maven-shade-plugin 把 `futu-api` + `protobuf-java 3.5.1` 打成一个 jar，`com.google.protobuf` 重定位到 `org.jdkxx.trader.shaded.futu.protobuf`；bcprov/bcpkix 作为直接依赖保留。一次性 `mvn install` 得到 `org.jdkxx.trader:futu-api-shaded`，主工程只依赖它。实测同一 JVM 内盈透 protobuf 生成类正常初始化，重定位后的富途 SDK 完成真实 OpenD 往返。

- 不做成 reactor 模块：IDEA 会把 reactor 模块当源码模块解析，IDE 里跑应用时 classpath 是未重定位的原始 jar。独立安装的构件让 IDE 与 Maven 看到同一个 jar。
- 业务代码仍 `import com.futu.openapi.pb.*`；只有直接调用 protobuf 运行时 API（JsonFormat、UnknownFieldSet）时才用重定位包名。
- 盈透 jar 不在 Maven Central，且许可证不允许公开再分发：`scripts/install-sdks.sh` 下载安装，jar 不入库。
- 运行时必须 JDK 21：protobuf 3.5.1 使用 `sun.misc.Unsafe`，JDK 24+ 告警、26 抛异常。

备选方案 B（拆独立进程）保留在路线图之外；模块边界保证以后想拆随时能拆。

## 4. 配置分层与敏感信息

| 层 | 文件 | 入库 | 内容 |
| --- | --- | --- | --- |
| jar 内默认 | `trader-app/src/main/resources/application.yml` | 是 | 与环境无关的默认值；默认 exclude DataSource/Flyway 自动配置；两家网关 `enabled=false` |
| 本机开发 | `config/application.yml` | 是 | 开发端口、只绑 127.0.0.1、`db_trader_dev`、`trader.environment: DEV`、`spring.config.import: optional:file:./config/secrets.yml` |
| 本机敏感 | `config/secrets.yml` | **否** | 数据库密码、网关 host/port/client-id/account、OpenAI key |
| 生产模板 | `deploy/config/application.yml` | 是 | 生产端口、`db_trader`、`trader.environment: PROD` |
| 生产敏感 | `deploy/config/trader.env` | **否** | `TRADER_DB_PASSWORD`、`TRADER_IBKR_*`、`TRADER_FUTU_*`、`OPENAI_API_KEY`，由 `bin/trader.sh` 载入，Spring 宽松绑定自动映射 |

- 启动一律 `--spring.config.additional-location=file:./config/`；IDEA 用入库的 `.run/TraderApplication (local).run.xml`。
- **数据库密码三条来源**，按优先级：环境变量 → `secrets.yml` → `~/.pgpass`。第三条由 `trader-storage` 的 `PgpassEnvironmentPostProcessor` 实现：`spring.datasource.password` 为空时按 libpq 规则匹配 host:port:database:username（回环地址互相等价），与 psql 共用同一份文件，密码不必复制到第二个地方。
- `scripts/check-secrets.sh` 扫描 IP、账户号形态、API key、私钥、明文口令；`scripts/install-git-hooks.sh` 装成 pre-commit。文档与注释同样不允许出现真实账户号、主机名、端口。

## 5. 环境与数据库隔离

- `trader.environment` 必填、无默认值，取值 `DEV | PROD`。
- `EnvironmentGuard` 以 `FlywayMigrationStrategy` 的身份挂在 Flyway 迁移之后立刻执行：Spring Boot 让所有依赖 DataSource 的 bean 等待 Flyway 初始化，因此守卫必然先于任何业务代码触库；校验失败抛异常，启动中止。
- 判定：库内 `app_environment` 标记与声明一致才通过。标记为空时，只有**本次启动前库里没有任何迁移记录**（`MigrateResult.initialSchemaVersion == null`）才自动盖章；已经建过表的库拒绝认领，防止把生产库改名。从生产 dump 灌进开发库后标记仍是 PROD，必须显式 `UPDATE` 一次——这是有意的。
- 开发与测试只连 `db_trader_dev`；生产 `db_trader` 只允许服务器上的生产实例连接。
- 表结构由启动时 Flyway 自动迁移（也可 `./mvnw -pl trader-storage flyway:migrate`）；迁移文件 `V<n>__<snake_case>.sql`，只增不改。约定：表名/列名 snake_case；时间 `timestamptz`（存 UTC，展示 America/New_York）；金额/价格 `numeric`；每表 `created_at` / `updated_at`。
- 定时任务 cron 一律显式 `zone: America/New_York`；同一台服务器只允许一个实例开调度（限频闸门是进程内计数）。

## 6. 端口与 client-id 分配

| 用途 | 值 |
| --- | --- |
| 本机开发实例 | 8083 |
| 生产实例 | 8093 |
| Vite dev server | 5174 |

网关侧的 host / port / client-id 只出现在 `secrets.yml` / `trader.env`。IB client-id 每个实例独占（撞车报 326 并把先连上的踢下线）：开发与生产各固定一个，临时验证实例另取；富途 `clientInfo` 用 `trading-signal` / `trading-signal-dev` 区分。

## 7. 前端

- `trader-web`：Vue 3.5 + Vite 8 + TypeScript 5.9（`vue-tsc -b` 类型检查）+ Element Plus + vue-router + pinia + axios。
- 开发：`npm run dev`（5174），`/api`、`/actuator` 代理到本机开发实例。
- 发布：`vite build` 输出到 `trader-app/src/main/resources/static/`，随 jar 同源提供；由 `scripts/package.sh` 触发，产物不入库。
- 第 0 期一个页面：系统信息（版本、环境、数据库、两家网关状态、AI 配置状态）。

## 8. 构建、运行、打包、版本

- 构建：`./mvnw clean verify`（Maven Wrapper 3.9.16；脚本自动探测 JDK 21 并校验大版本）。
- 运行：`./scripts/run-local.sh [stop]`——前台启动；stop 走 `POST /actuator/shutdown`，不发信号（只因 `server.address` 绑回环才敢开）。JVM 参数带 `-DsocksNonProxyHosts`，避免本机 SOCKS 代理劫持回环地址的 JDBC 连接。
- 打包：`./scripts/package.sh` → `dist/trading-signal-<版本>-<时间戳>.tar.gz`，含 `bin/trader.sh`、`config/application.yml`、`config/trader.env.example`、`lib/trader-app.jar`、`systemd/`。
- 测试：单元测试不碰库；集成测试（连 `db_trader_dev` / 真实网关）默认跳过，用 `-Dtrader.integration=true` 打开（第 1 期起）。
- 版本：父 POM `<revision>` + flatten-maven-plugin，一处改版本；开发一律 `-SNAPSHOT`，发布去掉并打 tag；递增规则见 README。`/api/system/info` 与 `/actuator/info` 报告 build-info 的版本与构建时间。

## 9. 第 0 期验收

- `./mvnw clean verify` 通过；`./scripts/run-local.sh` 启动后 `/actuator/health` 为 UP；`db_trader_dev` 上 Flyway 建出 `app_environment` 并盖上 `DEV`；`/api/system/info` 报告版本、环境、库、两家网关 `DISABLED`；`cd trader-web && npm run build` 产物随 jar 提供页面。

## 10. 第 1 期：网关接入层（2026-09-03 交付）

设计记录见 [design/phase1-gateway-layer.md](design/phase1-gateway-layer.md)，这里只记实现后的要点与实测结论。

### 10.1 共用状态机

`trader-gateway-api` 的 `ConnectionSupervisor` 与 SDK 无关：适配器只实现 `Transport`（`open()` 建连并等就绪、`close()`、`probe()` 心跳）。
状态 `DISCONNECTED → CONNECTING → CONNECTED`，断线或心跳连续 2 次失败进入 `RECONNECTING`，指数退避 5s×2 ≤ 60s（±20% 抖动，次数默认不限），
不可重试错误进入 `ERROR`。Transport 的 Future 一律 `whenCompleteAsync(scheduler)` 接回调度线程，**SDK 回调线程永远不跑状态机或监听器**；
`generation` 计数丢弃过期的异步结果。重连成功发 `onConnected(reconnected=true)`，第 2 期的订阅恢复挂在这里。
单元测试用虚拟时间的 `ManualScheduler`，结果完全确定。

### 10.2 盈透

- `IbkrConnection`：每次 open 新建 `EClientSocket`；`ibkr-connect` 线程跑同步握手 `eConnect`，`ibkr-reader`（EReader）+ `ibkr-pump`（`waitForSignal/processMsgs`）；就绪 = `nextValidId`。
- `IbkrWrapper extends DefaultEWrapper` 只做分发；`IbkrRequestRegistry` 的 reqId 从 10_000_000 起，与 orderId 空间分开；Future 完成转到 `ibkr-dispatch`。
- `currentTime` / `managedAccounts` 没有 reqId，用等待队列按到达顺序完成。
- 令牌桶 40 条/秒；请求超时 15s；心跳 `reqCurrentTime` 30s。
- 系统消息：2104/2106/2158 → `farm.<name>=OK`，2103/2105 BROKEN，2107/2108 INACTIVE；1100 只记事实；1101 → `notifyDataLost` 触发 reconnected 事件；326 明确提示 client-id 冲突。
- `trader.ibkr.account` 配置了但不在受管列表 → `ERROR`（不可重试）。
- **实测**（Gateway server version 223）：`primaryExch=NASDAQ` 可用（AAPL conId 265598，minTick 0.01，时区 US/Eastern）；查无此标的时 200 映射为空列表；中继切断后约 1 秒内重连成功。

### 10.3 富途

- 两条通道各一个 `FutuChannel` + 各自的 `ConnectionSupervisor`；网关状态 = 两者合成（都 CONNECTED 才算 CONNECTED；任一 ERROR → ERROR；任一 RECONNECTING → RECONNECTING）。监听器只收网关级事件。
- 每次 open 新建 `FTAPI_Conn_Qot` / `FTAPI_Conn_Trd`；就绪 = `onInitConnect errCode==0`；`FTAPI.init()` 进程内一次。
- `FutuReplyRegistry`：seq → Future，回复先于登记到达时暂存 early 表；`retType != 0` → `RequestRejectedException`。
- 探测：行情通道 `getGlobalState`（顺带把 OpenD 版本、登录状态、程序状态、港美市场状态写进 facts）；交易通道没有专门探测接口，用只读 `getAccList`（限频 10/30s，心跳 30s 一次远低于此）。
- 限频：滑动窗口 + 20ms 最小间隔，按接口名配置（`get-global-state 60/30s`、`get-acc-list 10/30s`）。
- **实测**：OpenD 1010.7008，两通道连接后 `programStatus=Ready`；账户列表含 1 个富途证券实盘（港美权限）与若干模拟账户；中继切断后 1 秒内重连。SDK 的 `onDisconnect` errCode 打印为 -4294967296（无符号转换），仅影响日志。

### 10.4 核心、存储、接口

- `GatewayLifecycle`（SmartLifecycle）：启动时对 `enabled && auto-connect` 的网关异步 `connect()`（网关不可达不影响应用启动），关闭时 `disconnect()`。
- `GatewayEventRecorder` 把连接事件写日志并落 `gateway_event`（V2），落库在自己的线程。
- `GET /api/gateways*`（见 API.md）；健康指标 `gateways`：启用但未连接 → `DEGRADED`（HTTP 200），部署脚本的就绪判断不受影响。
- 账户号只在服务端内存里，接口一律脱敏（`U1*****`）；facts 里没有主机、端口、账户号。

### 10.5 测试

- 单元 61 个：状态机（虚拟时间）、两个注册表、限流器、映射、服务与接口。
- 集成（`-Dtrader.integration=true`，连接参数从环境变量读，缺失即跳过）：`IbkrGatewayIT`、`FutuGatewayIT`、`ReconnectIT`（本地 TCP 中继切断后自动重连，两家都过）。

## 11. 第 2 期·步骤 1：日 K 线行情底座（2026-09-03 交付）

设计记录见 [design/phase2-step1-daily-bars.md](design/phase2-step1-daily-bars.md)，这里记实现要点与实测结论。

### 11.1 数据来源与额度策略（实测驱动）

- 富途美股板块没有标普 500 / 纳指 100 完整成分股；成分股来源为 Wikipedia 两页（`id=constituents` 表，按表头 Symbol/Ticker 取列），
  SSGA 的 SPY 每日持仓 xlsx 只做交叉核对（首轮：503 对 503，零差异），CSV 导入兜底。纳指表用 ICB 分类、标普用 GICS，原样存并记录体系。
- 富途历史 K 线额度是 7 天滚动 100 只（本账号），全量标的走**订阅轮转**：每批 ≤90 只 `sub(KL_Day)` → 逐只 `getKL(1000)` →
  停留满 65 秒 → `unsub`，零历史额度，518 只约 7 分钟。历史接口 `requestHistoryKL` 只用于池与持仓的 20 年深度（额度守卫预留 10 个）。
- 富途对不认识的代码在 `getStaticInfo` 里也会回一条（名称"未知股票"、brokerId=0、delisted=true），以 brokerId=0 判定 UNRESOLVED。

### 11.2 复权（实测结论，决定默认配置）

- 存不复权 K 线 + `rehab_factor`，读取时算：价 = 不复权价 × A + B，成交量不变。
- **富途的每条因子只是该事件自身的比例，不是累计值**：对 AAPL 2026-04-20～05-20（跨两次除息）用富途自己的前复权序列比对，
  逐事件复合（`FactorMode.PER_EVENT`）最大相对误差 1.8e-5，"取最近一条"（CUMULATIVE）为 8.5e-4。默认 `trader.marketdata.adjust.factor-mode=PER_EVENT`。
- 前复权对 exDate > 交易日 的事件按**从早到晚**复合：p' = A2(A1 p + B1) + B2；后复权对 exDate ≤ 交易日 的事件按**从晚到早**复合（后来的分红要按更早的拆股比例放大）。实测后复权与富途序列误差为 0（升序复合会差 2.5%）。

### 11.3 模块与流程

- 端口 `MarketDataGateway`（富途实现）：staticInfo / historyQuota / historyDailyBars（分页在适配器内）/ subscribe & unsubscribe & recentDailyBars / rehab / tradingDays。
  富途行情通道新增通用 `qotCall(限频名, 描述, 类型, 发送)`，各接口的限频在 `FutuProperties.DEFAULT_LIMITS`。
- `trader-core.marketdata`：`UniverseSyncService`（来源 → instrument 幂等 → 集合差 since/until → 静态解析）、`RotationRefresher`、`DeepBackfillService`（额度守卫 + 分页 + 因子）、
  `DailyIncrementService`（交易日历 → 缺口 = 交易日数 + overlap → 轮转补齐 → 池/持仓因子刷新）、`BarQueryService` + `BarAdjuster`、`PoolService`、`JobService`（单线程串行、job_run 落库、进度）、`MarketDataScheduler`（增量 ET 17:30 工作日，成分股同步周六 06:30；只在 `schedule-enabled` 的实例装配）。
- 整块只在 `trader.storage.enabled=true` 时装配（仓储依赖数据库）。
- 表：instrument、index_constituent、pool_member、daily_bar、rehab_factor、trading_day、bar_sync_state、job_run（V3）。
- 接口见 API.md「行情」；前端「行情」页：覆盖与额度卡片、跑批与作业记录、标的池维护、K 线查询（复权口径切换）。

### 11.4 已知边界

- 全量标的深度为最近 1000 根（约 4 年）；更早历史只对池与持仓（20 年）。
- 增量判定"当天已收盘"用美东 16:15 之后 + 交易日历；盘中触发只补到前一交易日。
- 反订阅失败（不足 1 分钟）只记警告，额度随连接关闭释放。

## 12. 第 2 期·步骤 2：实时报价订阅（不落库，2026-09-03 交付）

设计记录见 [design/phase2-step2-realtime-quotes.md](design/phase2-step2-realtime-quotes.md)。

- **只订 Basic**：期望集合 = 池 ∪ 持仓（富途已解析、未退市），每只 1 个订阅额度。实测（盘前）Basic 的 curPrice/volume 冻结在上个收盘、`preMarket` 子结构实时更新；逐笔在盘前没有成交推送；盘口推送活跃但本步骤不订（额度与流量，留到下单时按需）。
- **有效价按时段取**：`MarketSession` 由心跳拿到的 `marketUS` 判定（PreMarketBegin→PRE，Morning/Afternoon→RTH，AfterHoursBegin→AFTER，NightOpen→OVERNIGHT，其它 CLOSED），拿不到时按美东时钟；PRE/AFTER/OVERNIGHT 用对应子结构的 price/change/changeRate，RTH 与 CLOSED 用 curPrice 与昨收差。
- **对账**（`QuoteSubscriptionService`）：新增订阅、多余反订阅（未满 61 秒的延后到下次），触发点：富途连上（`auto-subscribe`）、重连（已订集合随连接清空后重订）、池增删（`PoolService.afterChange`）、手工；`pause()` 只反订阅满 1 分钟的（未满的延后，富途整批拒绝"订阅时间过短"），轮转前的暂停会等到全部满 1 分钟；`resume()` 重新对账。
- **与轮转协调**：`RotationRefresher.QuotaCoordinator`——`pause-during-refresh=true`（默认）时轮转前暂停、结束恢复；否则批次 = min(配置, 剩余额度 − 预留)。
- **缓存与推流**：`QuoteCache`（symbol → 带版本号的报价，推送总数 / 最近 60 秒计数）；`QuoteStreamService` 每秒把版本号增长过的报价合并成一帧 SSE `event: quotes`，15 秒一次 `event: status`；客户端断开自动清理；SDK 推送 → `futu-dispatch` 线程映射后交监听器，SDK 线程只做转交。
- 接口 `/api/quotes*`（见 API.md）；前端行情页「实时报价」表（EventSource，页面不可见时断开）与 lightweight-charts 日 K 图（蜡烛 + 成交量，随查询区间与复权口径切换）。
- 配置 `trader.marketdata.realtime.*`：开发机 `auto-subscribe=false`（手工对账），发布包 true；两个实例同时订会占双份额度。
- 报价不落库（设计原则）；进程重启后缓存为空，重订阅的首推即填充。
