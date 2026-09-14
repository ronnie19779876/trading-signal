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
| 3 | 账户与持仓（**已交付，§16**）：盈透账户资金与收盘持仓的每日快照与对账、持仓自动维护池里的 HOLDING 角色 |
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

实现要点与实测结论如下。

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

### 11.4 复权因子覆盖（数据质量核查后补充）

- 首轮核查（2026-09-03）：518 只全部对齐到最近交易日、无缺口、无坏值；但全量标的当时没有复权因子，原始价格在拆股日断层（NVDA 10:1、CMG 50:1 等）。
- 补充 `REHAB_REFRESH` 作业（`POST /api/bars/rehab/refresh?all=`）：全量各调一次 `requestRehab`（60/30s，不占历史额度，518 只约 5 分钟）；`bar_sync_state.rehab_fetched_at`（V4）记录刷新时刻。
- 每日增量作业末尾刷新池与持仓 + 全量里 7 天以上未刷新的因子（1.2.0 起每次最多全量的五分之一，见 15.5），全量因子按周滚动更新。
- NBIS（前身 Yandex）2022-02～2024-10 停牌，最近 1000 根跨到 2020 年，中间空档是真实停牌。

### 11.5 已知边界

- 全量标的深度为最近 1000 根（约 4 年）；更早历史只对池与持仓（20 年）。
- 增量判定"当天已收盘"用美东 16:15 之后 + 交易日历；盘中触发只补到前一交易日。
- 反订阅失败（不足 1 分钟）只记警告，额度随连接关闭释放。

## 12. 第 2 期·步骤 2：实时报价订阅（不落库，2026-09-03 交付）

- **只订 Basic**：期望集合 = 池 ∪ 持仓（富途已解析、未退市），每只 1 个订阅额度。实测（盘前）Basic 的 curPrice/volume 冻结在上个收盘、`preMarket` 子结构实时更新；逐笔在盘前没有成交推送；盘口推送活跃但本步骤不订（额度与流量，留到下单时按需）。
- **有效价按时段取**：`MarketSession` 由心跳拿到的 `marketUS` 判定（PreMarketBegin→PRE，Morning/Afternoon→RTH，AfterHoursBegin→AFTER，NightOpen→OVERNIGHT，其它 CLOSED），拿不到时按美东时钟；PRE/AFTER/OVERNIGHT 用对应子结构的 price/change/changeRate，RTH 与 CLOSED 用 curPrice 与昨收差。
- **对账**（`QuoteSubscriptionService`）：新增订阅、多余反订阅（未满 61 秒的延后到下次），触发点：富途连上（`auto-subscribe`）、重连（已订集合随连接清空后重订）、池增删（`PoolService.afterChange`）、手工；`pause()` 只反订阅满 1 分钟的（未满的延后，富途整批拒绝"订阅时间过短"），轮转前的暂停会等到全部满 1 分钟；`resume()` 重新对账。
- **与轮转协调**：`RotationRefresher.QuotaCoordinator`——`pause-during-refresh=true`（默认）时轮转前暂停、结束恢复；否则批次 = min(配置, 剩余额度 − 预留)。
- **缓存与推流**：`QuoteCache`（symbol → 带版本号的报价，推送总数 / 最近 60 秒计数）；`QuoteStreamService` 每秒把版本号增长过的报价合并成一帧 SSE `event: quotes`，15 秒一次 `event: status`；客户端断开自动清理；SDK 推送 → `futu-dispatch` 线程映射后交监听器，SDK 线程只做转交。
- 接口 `/api/quotes*`（见 API.md）；前端行情页「实时报价」表（EventSource，页面不可见时断开）与 lightweight-charts 日 K 图（蜡烛 + 成交量，随查询区间与复权口径切换）。
- 配置 `trader.marketdata.realtime.*`：开发机 `auto-subscribe=false`（手工对账），发布包 true；两个实例同时订会占双份额度。
- 报价不落库（设计原则）；进程重启后缓存为空，重订阅的首推即填充。

## 13. 第 2 期·步骤 3：基本面数据（2026-09-09 交付）

### 13.1 两类数据分开存

估值与财务报表的更新频率、取数成本、失效方式都不同，分两套表、两个作业，不塞进一张表。

| 数据 | 范围 | 频率 | 成本 |
| --- | --- | --- | --- |
| 估值快照 | 全量 ∪ 池 ∪ 持仓 ∪ 基准（521 只） | 每交易日收盘后 | 一次 400 只，两次调用几秒 |
| 财务报表 | 池 + 持仓（四类报表各 12 期） | 每周六；可手工全量回补 | 一次 1 只，全量 521 只约 42 分钟 |
| 公司简介 | 池 + 持仓 | 到期才刷（默认 30 天） | 一次 1 只 |

三个接口都不占订阅额度与历史 K 线额度，所以不会挤占已跑稳的日 K 线链路。
全量财报不进每周作业：绝大多数标的不看，要扩展到全量用 `stockFilter` 批量拿指标更划算。

### 13.2 财报存长表而不是宽表（关键取舍）

券商返回「字段字典 + 数据项」两段式，字段随行业与版本变化，且**同一编号在不同报表下含义不同**
（8001 在利润表是"总收入"，在资产负债表是"资产合计"；主要指标另起 14001~14050 段）。
因此 `financial_item` 用长表，主键是（报表行 id，字段编号），新增字段不用改表结构。
四类报表字段完全不重叠，四类都存。

### 13.3 实测决定的取值口径

- **估值字段在 proto 里是 required**，`has*()` 恒为 true，判空区分不出"有没有数据"。实测 400 只里市盈率与市值没有一个是 0，直接取值即可。
- **亏损股的市盈率市净率是负数**，不是 0 也不是缺失（实测 INTC 市盈率 TTM −49.99、LCID 市净率 −1.72）。负值原样入库，审计不把负值当异常。
- **Trust 类多数不给净值**（实测标普 500 里 25 只 REITs 净值全为 0，只有 SPY 有 769.35）。净值为 0 视作无数据，连同由它算出的溢价一并置空。
- **富途把美股 REITs 也归为 Trust**，被映射成 ETF。REITs 有财报，所以取财报不按类型过滤，真基金回空列表即可。
- **年报与四季报期末可能同一天**（实测英伟达 2026/FY 与 2026/Q4 都是 2026-01-24），唯一键必须带期别，否则必丢一份。财年可能领先自然年，排序与取"最近一期"一律用期末日期。

### 13.4 模块与表

- 端口新增 `snapshots` / `financials` / `companyProfile`；富途侧限流名 `get-security-snapshot` 60/30s、`get-financials` 25/30s（文档 30 实测会被拒）、`get-company-profile` 30/30s。
- `trader-core.marketdata.fundamentals`：`ValuationSnapshotService`、`FinancialsRefreshService`、`FundamentalsQueryService`、`FundamentalsAuditService`、`FundamentalsFacade`。
- 表：valuation_snapshot、financial_report（唯一键带期别）、financial_item（长表）、instrument.profile jsonb（V5）。
- 接口见 API.md「基本面」；前端「基本面」页：估值概览与序列、四类报表（列由数据决定）、公司简介。

## 14. 第 2 期·步骤 4：基准标的与交易日历（2026-09-10 交付）

### 14.1 基准单列一个角色

相对强弱、超额收益、贝塔都要有基准。标普用 SPY，纳指加 QQQ。SPY 起初是持仓（HOLDING），2026-09-14 改为 BENCHMARK：
第 3 期起 HOLDING 由盈透持仓自动维护，清仓会移出池，基准不能跟着买卖走。

**新增 `BENCHMARK` 角色而不是塞进 POOL**：基准不是候选买入标的，混在池里会让信号阶段把它当候选扫描，
这类错误很隐蔽。采集侧一视同仁（深度回补、每日增量、实时订阅都照做），选股侧用 `UniverseScope.candidates()` 排除。
池上限只约束 POOL。

### 14.2 交易日历两段拼接（券商只能回到 2016-09）

实测券商的日历有硬边界：请求 21 年与请求 27 年返回**完全相同**的 2591 天，都从 2016-09-12 开始。
所以日历分两段：

- **券商段**：一次请求拿到 2016-09 至今，`source=FUTU`。
- **反推段**：更早的从池/持仓/基准的日 K 线反推。这些是大盘股，每个交易日都有成交，
  出现过的日期并集就是那段时间的交易日，`source=DERIVED`，只补空缺不覆盖券商数据。

反推要求当天**至少 2 只标的同时成交**：券商在三个美股假日给过 SPY 脏 K 线（成交额 0），
只按"有没有 K 线"取并集会把假日算进来。真实交易日有 13 只以上同时成交，阈值 2 既挡得住脏数据又离真实值很远。

**验证**：用 NYSE 假日规则在本地独立生成 2006-08-21 至 2026-09-18 的日历（含耶稣受难日、六月节、
节假日顺延、2007 年福特葬礼与 2012 年桑迪飓风休市），与库里逐日比对，券商段 2519 天、反推段 2532 天，**双向零差异**。

### 14.3 日历带来的检查能力

前收连续性检查查不出券商的历史缺口：实测 SPY 缺了 26 个交易日（2009~2012），而券商自己的前收与缺口自洽。
只有拿日历比对才查得到。因此审计增加 `historyGaps`（最近 90 天）与 `GET /api/bars/gaps`（全历史深扫）。

## 15. 第 2 期·步骤 5：作业可靠性与数据订正（2026-09-10 交付）

### 15.1 定时作业不再静默丢失

作业执行器单线程，提交时已有作业在跑会抛异常，原先只打一行 WARN 就算了。
而增量与估值只隔 10 分钟，增量实测要 6.5 分钟，超时估值就被丢弃，**估值是时点数据，当天不补就永远没有**。

三层防护：

1. **碰撞重试**：每 5 分钟一次、最多 6 次（覆盖半小时），重试跑在自己的线程上不占作业线程。
2. **当天补偿检查**（美东 21:00）：确认当日 K 线与估值都齐，缺什么补什么。盖得住重试盖不住的情况，
   比如该跑的时候应用正在重启、作业 FAILED、服务器没开。
3. **SKIPPED 留痕**：重试到底仍失败写一行 `job_run`，日志会随部署清理丢掉，库里的不会。

**补救窗口是 12 小时**：券商收盘后冻结当前价直到次日盘前（16:00 ET → 次日 04:00 ET）。
实证：在美东 20:49 取快照写入的仍是当日收盘口径，市值等于股本乘当日收盘价误差 0.0000%。补偿检查排在 21:00 ET，离窗口关闭还有 7 小时。

**生产验证**：2026-09-10 首夜即触发——增量因全量复权因子同时到期跑了 691 秒，估值在 17:40 提交失败，
重试于 17:45 成功。没有这层防护当天估值会永久丢失。

### 15.2 让失败看得见

- `jobs` 健康指标（与 `gateways` 同样的 DEGRADED 语义）：任一定时作业 FAILED / SKIPPED / 逾期未跑即降级。
- `scripts/check-daily.sh` 合并三段：日线审计 + 基本面审计 + 运行健康。此前只调日线审计，
  恰好漏掉唯一能发现估值缺失的检查。

### 15.3 幽灵 K 线订正

券商在美股假日给过脏 K 线（SPY 三根，成交额 0，独立日那根凭空造出 15% 日内暴跌）。
日历排除了它们，但 K 线表里还在，按标的自身序列遍历的回测会多出交易日。

`POST /api/bars/cleanup/phantom?apply=false` 默认只试跑列清单。安全前提是日历在该区间已被独立验证完全正确，
所以"不在日历里"等价于"不该存在"；日历没覆盖的年份一律不碰。审计增加提示项 `phantomBars`。

### 15.4 明确不做的

- **外部告警通道**（邮件、webhook）：需要投递渠道与密钥，密钥不进仓库，要先定渠道再单独设计。健康指标已把状态暴露成机器可读。
- **作业排队**：会让手工提交从"立刻知道有作业在跑"变成"排队等着"，破坏现有 409 语义。

### 15.5 观察期发现、1.2.0 补齐的两处

- **复权因子集中到期**：全量标的若同一天刷过，7 天后会在同一次增量里一起到期，多花约 300 秒（09-10 因此跑了 691 秒）。
  改为到期的按最久未刷优先、每次增量最多刷全量的 1/`refresh.rehab-spread-days`（默认 5，约 105 只）。
  集中到期会在几个交易日内自己摊开并保持摊开；代价是个别标的的因子最晚约 14 天才刷（单测模拟八周验证）。
  池、持仓、基准仍每天刷，不受影响。
- **补偿检查留痕**：此前数据齐全时只打日志，它自身停摆无人察觉。现在每次运行都写一行 `job_run`（作业名 `CATCHUP_CHECK`：
  非交易日、都齐了、发现缺口已提交补跑记 OK，检查本身出错记 FAILED）。`jobs` 健康指标纳入监控：
  最近一次 FAILED，或跑过却 96 小时没成功（`catchupOverdue`）即降级；刚上线从未跑过不算。

## 16. 第 3 期：账户与持仓（2026-09-14 交付，2.0.0）

目标：每个交易日收盘后把盈透账户的资金与持仓存成快照并对账，持仓自动维护池里的 HOLDING 角色。全部只读，不下单。

### 16.1 实测（2026-09-14，TWS API 10.30.01，对真实网关只读探针）

| 实测 | 结论 |
| --- | --- |
| `reqPositionsMulti` 约 0.5 秒回齐，带 reqId，结束回调按时到，连发两次正常，取消后无残留推送 | 持仓用它，套请求注册表 |
| 持仓回调 `primaryExch` 为 null、`exchange` 有值；类别股代码带空格（`BRK B`）；数量可为小数 | 映射以 conId 为主，首次按"空格→点"找标的 |
| `reqAccountSummary` 约 1 秒回齐；取消后仍会再推一两条 | 一次性"请求→收齐→取消"，迟到的推送忽略 |
| `$LEDGER-AccountOrGroup` 的值就是**明文账户号** | 映射时剔除，日志、异常、返回里都不出现 |
| 现金 + 股票市值 + 应计股息 = 净值，精确到分 | 作对账项 |
| 收盘后盈透标价与富途官方收盘逐只差 ≤0.1%（个别 0.6%）；按富途收盘算的总市值与盈透股票市值只差十万分之一量级 | 持仓按富途收盘估值，与盈透股票市值对账 |
| `reqPnL` / `reqPnLSingle` 休市时首条不完整，行情农场连上后改用盘外价重算（单价偏离收盘最多 1.7%） | 不进每日快照 |
| `reqAccountUpdates` 同一账户同时只允许一个客户端订阅 | 不用——开发与生产实例会互相干扰 |
| 富途快照的当前价收盘后冻结在常规时段收盘，时间戳却跟着盘后/夜盘走（美东周日 20:52 取 SPY：时间戳是周日，价格 764.29 = 周五 K 线收盘） | 快照价兜底时不能看时间戳判断属于哪天，只在下一个交易日 04:00 前采用 |

### 16.2 决策

1. **接口**：持仓 `reqPositionsMulti`，资金一次性 `reqAccountSummary`（常规标签 + `$LEDGER:USD`）；新增 `AccountGateway` 端口，core 只认接口。
2. **估值价格**：持仓市值 = 数量 × 富途当日官方收盘，与 K 线、信号同源；盈透股票市值只做对账的另一边。
   库里没有当日 K 线的持仓（比如只进快照的现金管理工具）用富途快照价兜底，按上面实测只在下一个交易日 04:00 前采用；
   每条持仓记价格来源 BAR / SNAPSHOT / NONE。
3. **快照时点**：美东 18:00 工作日（交易日才跑），21:00 补偿检查兜底。盈透只给"现在"不给历史，漏掉的日子补不回来，记 SKIPPED。
   快照窗口是交易日 16:15 至次日 04:00，窗口外手工拍直接 409；`force=true` 只在开发环境可用，用于验证。
4. **对账**：账户级市值（容差 0.2%）、资金恒等式（容差 1 美元）、持仓集合与 HOLDING 一致、映射与收盘价齐全；结果 OK / WARN / FAIL。
5. **账户号**：库里只存带密钥的 HMAC 与脱敏形式，明文只在服务端内存；多账户时按 `trader.ibkr.account` 选。
   不用纯哈希：盈透账户号只有约 10^8 种取值，几秒就能穷举回去。密钥 `trader.account.key-secret` 在外置配置，启用后不要更换。
6. **代码映射**：标的表加 `ibkr_con_id`；非股票持仓只进快照不进池；用户指定的现金管理类持仓（排除名单放外置配置）也只进快照不进池。
7. **HOLDING 自动维护**：快照后、盈透连上时、手工试跑时触发。新买入标 HOLDING（原为 POOL 的记下）；清仓后原为 POOL 的回 POOL，
   其余移出池（数据保留）；BENCHMARK 成员不因买卖改角色。请求不完整、或返回空而上次非空时不改；HOLDING 不再接受手工添加。
8. **日盈亏**查询时现算：净值变化（含出入金）与昨日持仓 × 今昨收盘差；区分出入金留到第 5 期。

### 16.3 步骤

| 步 | 内容 | 状态 |
| --- | --- | --- |
| 1 | `AccountGateway` + 盈透实现 + 只读集成测试 | 已完成（2026-09-14） |
| 2 | V9 存储、快照作业、对账 | 已完成（2026-09-14） |
| 3 | HOLDING 自动维护 | 已完成（2026-09-14） |
| 4 | 接口、前端、巡检、文档，发布 2.0.0 | 已完成（2026-09-14） |

步骤 1 要点：两个请求都在 future 结束时（收齐、失败、超时）挂取消，取消跟着 future 在 dispatch 线程上发，不占泵线程；
同一账户并发的汇总请求合并成一次（网关对账户汇总订阅有全局并发上限）；数量无效时报错而不记成 0（记成 0 会被当成清仓）。

步骤 1 集成测试（真实网关，休市时，只读）：持仓连查两次条数一致（取消干净）；两个并发汇总拿到同一结果对象（合并生效）；
紧接着第三次汇总照常返回（没有被网关拒绝）；汇总 31 个标签无缺失字段，恒等式偏差 0.0000%；
连接预热后两次持仓加三次汇总共约 0.36 秒。测试期间生产实例的盈透连接无任何断连事件。

步骤 2 要点：V9 加 `account_snapshot` / `position_snapshot`、`instrument.ibkr_con_id`，以及步骤 3 要用的 `pool_member.source / return_role`；
作业 `ACCOUNT_SNAPSHOT`（碰撞重试沿用从行情调度器抽出来的 `ScheduledSubmitter`），`jobs` 健康指标监控它，
逾期阈值放宽到 120 小时（只在交易日跑，长周末周五到周二正好 96 小时）。
开发实例实测（force，按周五口径，真实持仓）：8 条持仓全部映射，价格 K 线 2 条 + 快照 6 条、零缺价；
恒等式差 0.00，市值对账差 0.0028%；持仓集合 WARN 列出一只持有但池里没标 HOLDING 的（步骤 3 自动维护）；
同一天重拍覆盖为一行，持仓整体替换。

步骤 3 要点：`HoldingSyncService` 按持仓维护 HOLDING——持有而池里没有的加入（来源 IBKR，库里没有的先向富途解析建档）、
POOL 升 HOLDING 并记下原角色、清仓后原为 POOL 的回 POOL、其余移出池（数据保留）。**BENCHMARK 不动**：这是设计时漏看、
开工前查出来的——标普基准用的就是持仓里的 SPY，照"清仓移出池"会把基准弄丢；对账也把持有的基准算作一致。
每处改动带状态条件（池被别处改过就跳过并报出），盈透返回空持仓而池里有 HOLDING 时不执行。
触发：快照作业里先同步再对账、生产实例盈透连上 60 秒后、手工 `POST /api/account/holdings/sync`（默认只看计划）；
手工 `POST /api/pool/{symbol}?role=HOLDING` 改为 409。新增或升级的标的由 `ScheduledSubmitter` 排深度回补（快照作业占着线程时延后重试）。
开发实例实测：计划"加入 HOLDING IBKR" → 应用后深度回补到 20 年 → 快照对账四项全 OK → 再同步"无需变动"。

第 2 期步骤 2 起的遗留问题，步骤 4 修掉：池变动钩子触发的实时订阅对账原先不看 `auto-subscribe`，
开发实例上加减池成员（手工或持仓同步）会让开发实例也订阅实时报价，违背"两个实例不要同时订阅"。
现在钩子走 `QuoteSubscriptionService.onPoolChanged`：`auto-subscribe=false` 且当前没有订阅时不对账，手工对账过的照常跟随。

步骤 4 要点：账户审计 `GET /api/account/audit` 进收盘巡检第三段——快照定在美东 18:00，巡检常在前后脚跑，
所以 18:30 前缺快照只提示，过了才判关键项失败；从来没有过快照（刚启用）也只提示，与"停摆"区分开；对账 FAIL 是关键项，WARN 与缺价只提示。最新快照附日变化（查询时现算：
净值变化含出入金、两份快照都持有的 上一份数量 × 价差，当天有买卖时标近似）。前端"账户"页只读展示，
持仓同步要先看计划再二次确认执行。SPY 同日由 HOLDING 改为 BENCHMARK（§14.1）。
开发实例实测：不暂停订阅改池，开发实例订阅仍为 0；快照对账四项 OK，持有的基准按基准保留；审计与巡检账户段通过。

2.0.1 修掉的早期问题：前端用 history 路由，但后端原先没有 SPA 回退，直接打开或刷新 `/marketdata`、`/fundamentals`、`/account`
这类深链接会 404（从首页点进去正常）。现在 `SpaForwardController` 把单段、不含点、不是 api / actuator / error 的路径
转发给 index.html；测试逐个核对前端路由表，新增多段路由会失败提醒。
