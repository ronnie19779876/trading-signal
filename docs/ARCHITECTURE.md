# 架构设计说明书

> 版本：1.0（第 0 期骨架，2026-09-03）。设计方案已于 2026-09-03 经用户认可。
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
| 0 | **骨架**（本文）：多模块、配置分层、环境守卫、健康页、前端骨架、脚本与部署模板 |
| 1 | 网关接入层：连接管理 / 断线重连 / 健康探测、请求-回调关联、限频与额度闸门；系统页显示真实网关状态 |
| 2 | 行情数据底座：标的池、历史日 K 线全量与增量（富途）、复权口径、实时订阅（不落库）、查询接口 |
| 3 | 账户与持仓：盈透账户资金、收盘持仓与盈亏的每日快照与对账 |
| 4 | 基本面与 AI 分析：富途基本面增量、入场信号判据、OpenAI 结构化分析、信号页 |
| 5 | 下单链路 + 风控（盈透）：先人工确认制，再逐步自动化 |

## 2. 模块与依赖方向

```
trading-signal/
├── trader-common          基础工具：AppEnvironment、MarketClock（America/New_York）、Masking。无 Spring、无 SDK
├── trader-domain          领域模型：Broker（含职责）、Market、Instrument。无 Spring、无 SDK
├── trader-gateway-api     网关端口：BrokerGateway、GatewayStatus/GatewayState。上层只依赖这里
├── trader-gateway-ibkr    盈透适配器：IbkrProperties、IbkrGateway、mapper.IbkrContracts（com.ib.client.* 只在这里）
├── trader-gateway-futu    富途适配器：FutuProperties、FutuGateway、mapper.FutuSecurities（com.futu.openapi.* 只在这里）
├── trader-storage         PostgreSQL：Flyway 迁移、EnvironmentGuard、pgpass 密码来源、DatabaseStatusService
├── trader-ai              OpenAI 接入：AiProperties、OpenAiClientFactory（com.openai.* 只在这里）
├── trader-core            业务编排：SystemInfoService（第 0 期）；后续采集、信号、风控、订单服务
├── trader-app             Spring Boot 启动、REST、静态前端、fat jar；把两家适配器装配进核心
├── trader-web             Vue 3 + Vite + TypeScript + Element Plus（npm 工程，不是 Maven 模块）
└── sdk/                   tws-api 安装、futu-api-shaded 重定位工程
```

依赖方向（模块顺序即依赖顺序，不得反向）：

```
app → { core, gateway-ibkr, gateway-futu }
core → { gateway-api, storage, ai }
gateway-ibkr / gateway-futu → gateway-api → domain → common
storage → common ；ai → common
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
