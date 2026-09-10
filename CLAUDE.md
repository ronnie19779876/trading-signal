# CLAUDE.md

基于 AI 大模型的美股量化交易系统。Java 21 + Spring Boot 3.5.13 + PostgreSQL 18 + Vue 3。
本仓库**公开托管在 GitHub**：账户号、密码、密钥、主机、端口、client-id 一律不入库、不进文档、不进注释。

> 设计与运维细节都在文档里，本文只放每次开工必须知道的：
> [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) · [docs/API.md](docs/API.md) · [docs/OPERATIONS.md](docs/OPERATIONS.md) · [CHANGELOG.md](CHANGELOG.md)

## 工作约定

1. **任何新功能先出设计方案，得到用户明确认可后才写代码。** 分期推进，先易后难；每期开工前单独讨论。
2. **敏感信息纪律**：见 README「敏感信息纪律」。提交前 `./scripts/check-secrets.sh` 必须通过（`./scripts/install-git-hooks.sh` 装成 pre-commit）。
3. **开发与测试只连 `db_trader_dev`**。生产库 `db_trader` 只允许服务器上的生产实例连接。`EnvironmentGuard` 会拦截错连，但不要依赖它当保险。
4. **实测优先**：券商 SDK 的行为常与文档不符，涉及 IB / 富途行为的判断先 `javap` 查 jar 真实签名或对真实网关跑一次再下结论。
5. **全新编写、独立思考**：`~/Projects/ib-auto-trader` 与 `~/Projects/futu-trader` 是用户的两个私有项目，只借鉴模式，不搬代码。
6. 版本只改父 POM 的 `<revision>`；规则见 README。
7. **新增 / 改动 REST 接口必须同步三处**：`docs/API.md`、`docs/postman/build_collection.py` 的 `ENDPOINTS`（改完重跑生成 JSON，用户要重新导入 Postman）、前端 `trader-web/src/api/*.ts`（若页面用到）。Postman JSON 是生成物，不要手改。

## 命令

```bash
./scripts/install-sdks.sh                 # 克隆后一次：安装 tws-api 与 futu-api-shaded 到本地仓库
./mvnw clean verify                       # 全量构建 + 单元测试
./mvnw -pl trader-storage -am -Dtest=EnvironmentCheckTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl trader-app -am -DskipTests package && ./scripts/run-local.sh     # 本机开发实例 :8083
./scripts/run-local.sh stop               # POST /actuator/shutdown，不发信号
cd trader-web && npm run dev              # Vite :5174，/api 与 /actuator 代理到 8083
cd trader-web && npm run build            # 产物进 trader-app/src/main/resources/static（gitignore）
./scripts/package.sh                      # dist/trading-signal-<版本>-<时间戳>.tar.gz
./scripts/check-secrets.sh                # 敏感信息扫描
./scripts/check-daily.sh [baseUrl]        # 收盘后日线数据审计（GET /api/bars/audit）
# 集成测试（对真实网关只读；参数从环境变量读，见 OPERATIONS §2）
./mvnw -pl trader-app -am verify -Dtrader.integration=true -Dtest='IbkrGatewayIT,FutuGatewayIT,ReconnectIT' -Dsurefire.failIfNoSpecifiedTests=false
```

- 脚本自己探测 JDK 21（`scripts/lib/jdk.sh`），不依赖 `JAVA_HOME`；直接跑 `./mvnw` 时非交互 shell 可能没有 `JAVA_HOME`，需要显式指定 JDK 21。
- `mvnw` 使用 Maven 3.9.x；本地仓库位置由 `~/.m2/settings.xml` 决定，必须与安装 SDK 时用的同一个。依赖齐了以后加 `-o` 离线构建：本机在线构建曾因网络检查卡住十分钟，离线全量 verify 约 10 秒。
- 带 `-Dsurefire.failIfNoSpecifiedTests=false` 时测试名打错会静默通过，认结果前确认输出里有 `Tests run: N`。

## 端口分配（撞车的表现都不像撞车）

| 用途 | 值 |
| --- | --- |
| 本机开发实例 | 8083 |
| 生产实例 | 8093 |
| Vite dev server | 5174 |

网关侧的 host / port / client-id 只存在于 `config/secrets.yml` 与 `deploy/config/trader.env`。
IB client-id 每个实例独占（撞车报 326 并把对方踢下线）：开发与生产各自固定一个，临时验证实例另取。

## 模块与依赖方向

```
app → { core, gateway-ibkr, gateway-futu }
core → { gateway-api, storage, ai }
gateway-ibkr / gateway-futu → gateway-api → domain → common
storage → common ；ai → common
```

- `com.ib.client.*` 只在 trader-gateway-ibkr；`com.futu.openapi.*` 只在 trader-gateway-futu；`com.openai.*` 只在 trader-ai。
  三处都是 `<optional>true</optional>`，trader-app 另有 runtime 声明只为打包——删掉它构建照过、启动就 NoClassDefFoundError。
- core 只认 `trader-gateway-api` 的接口；适配器在 app 层插入。
- 存储用 JdbcTemplate + Flyway（`trader-storage/src/main/resources/db/migration/V<n>__<snake>.sql`，只增不改），不用 JPA，不用 Lombok。
- 网关线程纪律：SDK 回调线程（`ibkr-pump`、富途 `FTAPI4JNet`）上只做分发；Future 的完成转到 `*-dispatch`；状态机与监听器跑在 `*-scheduler`；监听器里落库要转到自己的线程（见 `GatewayEventRecorder`）。
- 新增一种 TWS 请求：在 `IbkrWrapper` 里把数据回调交给 `registry.item`、结束回调交给 `registry.complete`；无 reqId 的请求用等待队列（参考 `currentTime`）。新增一种富途请求：在 `FutuChannel` 的 SPI 里把 `onReply_*` 交给 `registry.onReply`，调用处用 `registry.call(...)` 包住 SDK 调用并先过限流器。

## 坑（已实测）

| 坑 | 应对 |
| --- | --- |
| 富途 SDK 的 protobuf 生成代码是 3.5.1，盈透 TWS API 10.30 要 protobuf-java 4.x，同一 classpath 必炸 | 只依赖 `sdk/futu-api-shaded` 产出的重定位版本，绝不直接依赖 `com.futunn.openapi:futu-api` |
| protobuf 3.5.1 用 `sun.misc.Unsafe` | 运行时必须 JDK 21（24+ 告警、26 抛异常） |
| jar 内配置默认 exclude 了 DataSource/Flyway 自动配置 | 外置配置里 `trader.storage.enabled=true` 必须配合 `spring.autoconfigure.exclude: []` |
| JDBC 不读 `~/.pgpass` | 本项目自带 `PgpassEnvironmentPostProcessor`：`spring.datasource.password` 为空时按 libpq 规则从 `~/.pgpass` 取，回环地址互相等价 |
| Mac 上开着系统级 SOCKS 代理时 pgjdbc 连不上 127.0.0.1 | 启动加 `-DsocksNonProxyHosts='localhost|127.*|[::1]'`（脚本与 IDEA 运行配置已带） |
| 启动日志 `Flyway upgrade recommended: PostgreSQL 18.6 is newer than this version of Flyway` | Spring Boot 3.5.13 BOM 带的 Flyway 11.7.2 只声明支持到 PG 17；实测 V1 迁移正常。升级 Flyway 需在父 POM dependencyManagement 里显式钉版本，另议 |
| 盈透 jar 不在 Central 且许可证不允许再分发 | `scripts/install-sdks.sh` 下载安装；`.gitignore` 排除 `*.jar` |
| TWS `Contract.secType()` 返回枚举，`getSecType()` 才是字符串 | 断言与映射用 `getSecType()` |
| 富途 proto2 枚举字段（如 `ProgramStatus.getType()`）返回枚举而不是 int | 直接比较枚举常量，别用 `_VALUE` |
| 富途交易通道没有探测接口 | 心跳用只读 `getAccList`（限频 10/30s，30 秒一次够用） |
| IB Gateway 上 `primaryExch=NASDAQ` 已实测可用（AAPL conId 265598） | 不必改用 ISLAND |
| 富途历史 K 线额度每 7 天只有 100 只 | 全量标的走订阅轮转 `sub(KL_Day)+getKL(1000)`（零额度）；`requestHistoryKL` 只给池与持仓 |
| 富途复权因子是逐事件比例，不是累计值（实测 1.8e-5 vs 8.5e-4）；前复权从早到晚复合、后复权从晚到早复合 | `factor-mode` 保持 PER_EVENT，别改复合方向 |
| 富途美股板块没有标普/纳指完整成分股 | 成分股来自 Wikipedia，SPY 持仓交叉核对，CSV 兜底 |
| `getStaticInfo` 对不认识的代码也回一条（"未知股票"、brokerId=0） | 以 brokerId=0 判 UNRESOLVED |
| 富途 Basic 报价在盘前盘后 curPrice/volume 冻结，只更新 preMarket/afterMarket 子结构；逐笔盘前无成交推送 | 有效价按时段取（`FutuQuotes`），别拿 curPrice 当实时价 |
| 盘前盘后券商的 `lastClose`（昨收）**也冻结**，仍是上一个常规时段的前收；券商却是拿冻结的 curPrice 算盘前涨跌的（实测 AAPL 盘前昨收 319.97、实际基准 316.22） | 展示与计算基准一律用 `Quote.referenceClose`，别用 lastClose |
| 订阅额度按「标的 × 类型」计，账户共 100；订阅满 1 分钟才能反订阅 | 只订 Basic；反订阅前查订阅时刻，未满的延后 |
| 快照的估值字段在 proto 里是 required，`has*()` 恒为 true；亏损股的市盈率市净率是**负数**不是 0（实测 INTC -49.99、LCID -1.72） | 直接取值，负值原样入库；别用 has() 判有无，也别把负值当异常 |
| 富途把**美股 REITs 也归为 Trust**（实测标普 500 里 25 只 REITs 全是 Trust，被我们映射成 ETF；真 ETF 只有 SPY）。REITs 有财报，按"ETF 没财报"跳过会漏掉 25 只 | 取财报不按类型过滤，真基金回空列表即可；判断"该不该有财报"看有没有取到，别看 secType |
| 富途对 Trust 类多数不给净值，用 0 占位（实测 25 只 REITs 净值全 0，SPY 有 769.35） | 净值为 0 视作无数据，连同溢价一起置空 |
| 财报的年报与四季报**期末是同一天**（实测英伟达 2026/FY 与 2026/Q4 都是 2026-01-24）；财年可能领先自然年（`fiscalYear=2027`） | 唯一键必须带期别；排序取"最近一期"用期末日期，不要用财年 |
| 财报字段编号在不同报表类型下含义不同（8001 在利润表是总收入，在资产负债表是资产合计） | 数据项按（报表行, 字段编号）定位，不要跨报表复用编号 |
| 财报按 30/30s 发会被富途拒（同复权因子） | 限流配 25/30s 留余量 |
| 新增富途请求后忘了在 `FutuChannel` 的 SPI 里注册 `onReply_*`，表现是**请求超时**而不是编译错误（实测快照 400 只只要 228ms，超时必是没注册） | 加请求必同时加 SPI 回调，见"模块与依赖方向"最后一条 |
| 券商的**交易日历只能回到约 2016-09**（实测请求 21 年与 27 年返回完全相同的 2591 天），更早的取不到 | 早年日历从池/持仓/基准的日 K 线反推，`trading_day.source` 标 DERIVED |
| 富途给 SPY 在美股假日留了**脏 K 线**（实测 2011-07-04、2012-04-06、2012-05-28：成交额 0、最低价异常） | 反推日历要求当天≥2 只标的同时成交；真实交易日有 13 只以上，脏数据只有 1 只 |
| 富途的 SPY 历史**缺 26 个交易日**（2009~2012），而且它自己的 `last_close` 与缺口自洽，所以**前收连续性检查查不出来** | 查历史缺口必须拿交易日历比对，别只信连续性检查 |
| 新增 `@Scheduled` 用的配置项只写在 `MarketDataProperties` 的 `@DefaultValue` 上不够——**占位符解析不看记录默认值**。开发实例 `schedule-enabled=false` 不装配调度器，本地全绿、一上生产就起不来 | 新 cron 必须同时写进 jar 内 `application.yml`；`ScheduledPlaceholdersTest` 会守住这条 |

## 当前状态

- **第 0 期骨架、第 1 期网关接入层已交付**（1.0.0-SNAPSHOT，2026-09-03）：连接 / 重连 / 心跳、请求-回调关联、限频、账户与合约查询、事件时间线（V2 `gateway_event`）、`/api/gateways*`、系统页实时状态。61 个单元测试 + 4 个集成测试（真实网关 + TCP 中继断线重连）全过。
- 本机 `config/secrets.yml` 已启用两家网关（隧道 + 开发 client-id）；入库的 `config/application.yml` 仍是 `enabled: false`。
- **第 2 期步骤 1 日 K 线底座已交付**（2026-09-03）：成分股同步（518 只）、全量轮转 1000 根、池/持仓 20 年深度、复权读取、每日增量、作业记录、`/api/universe* /api/pool* /api/bars* /api/jobs*`、前端「行情」页。
- **第 2 期步骤 2 实时报价已交付**（2026-09-03）：池 + 持仓 Basic 订阅对账、内存缓存、SSE 推流、与轮转的额度协调、`/api/quotes*`、前端实时表与 K 线图。数据质量核查后补了全量复权因子作业（V4）。
- **第 2 期步骤 3 基本面数据已交付**（1.1.0-SNAPSHOT，2026-09-09）：估值快照、四类财报、公司简介、基本面审计、前端「基本面」页；生产已全量回补。
- **第 2 期步骤 4 基准与日历（开发中）**：`BENCHMARK` 角色（QQQ 作纳指基准）、交易日历回补到 2006 年（券商段 + 反推段）。
