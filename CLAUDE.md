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
6. 版本只改父 POM 的 `<revision>`；规则见 README。**部署到生产的必须是正式版，`-SNAPSHOT` 只能留在开发与测试**。
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
./scripts/check-daily.sh [baseUrl]        # 收盘后巡检五段：日线、基本面、账户、信号审计 + 运行健康
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
| 富途**日 K 自带的市盈率**（`daily_bar.pe`，静态口径，接近估值快照的 `pe` 而不是 `pe_ttm`）在亏损期与基金上给 **0**，而估值快照给负数（2026-09-19 实测 INTC 日 K 当前 0、快照 -49.99；SPY 日 K 全是 0） | 日 K 的 pe 为 0 视作无效：不画、不进分位统计；要看亏损股的市盈率用估值快照 |
| 财报的年报与四季报**期末是同一天**（实测英伟达 2026/FY 与 2026/Q4 都是 2026-01-24）；财年可能领先自然年（`fiscalYear=2027`） | 唯一键必须带期别；排序取"最近一期"用期末日期，不要用财年 |
| 财报字段编号在不同报表类型下含义不同（8001 在利润表是总收入，在资产负债表是资产合计） | 数据项按（报表行, 字段编号）定位，不要跨报表复用编号 |
| 财报按 30/30s 发会被富途拒（同复权因子） | 限流配 25/30s 留余量 |
| 新增富途请求后忘了在 `FutuChannel` 的 SPI 里注册 `onReply_*`，表现是**请求超时**而不是编译错误（实测快照 400 只只要 228ms，超时必是没注册） | 加请求必同时加 SPI 回调，见"模块与依赖方向"最后一条 |
| 券商的**交易日历只能回到约 2016-09**（实测请求 21 年与 27 年返回完全相同的 2591 天），更早的取不到 | 早年日历从池/持仓/基准的日 K 线反推，`trading_day.source` 标 DERIVED |
| 富途给 SPY 在美股假日留了**脏 K 线**（实测 2011-07-04、2012-04-06、2012-05-28：成交额 0、最低价异常） | 反推日历要求当天≥2 只标的同时成交；真实交易日有 13 只以上，脏数据只有 1 只 |
| 富途的 SPY 历史**缺 26 个交易日**（2009~2012），而且它自己的 `last_close` 与缺口自洽，所以**前收连续性检查查不出来** | 查历史缺口必须拿交易日历比对，别只信连续性检查 |
| Spring 组件有**两个公开构造器**（一个给容器、一个给测试）时，容器选不出来就去找无参构造器并在启动时炸；只在生产装配的 bean（`@ConditionalOnProperty`）本地压根不创建，发现不了 | 只留一个公开构造器，测试要替换依赖就用可写字段；`SpringBeanConstructorTest` 会守住。注意别用 Spring 的类路径扫描写这种检查——它会评估 `@Conditional` 跳过这些类，测试会假通过 |
| 盈透账户汇总的 `$LEDGER-AccountOrGroup` 值是**明文账户号**（2026-09-14 探针首跑就漏打过一次） | 映射时剔除（`IbkrAccounts`），日志、异常、返回都不带；临时探针脚本也要打码 |
| 盈透 `reqAccountUpdates` 同一账户同时只允许一个客户端订阅；`reqPnL`/`reqPnLSingle` 休市时首条不完整、行情连上后改用盘外价重算 | 持仓用 `reqPositionsMulti`、资金用一次性 `reqAccountSummary`；盈亏不进快照 |
| 盈透持仓的类别股代码带空格（`BRK B`），`primaryExch` 为 null | 映射以 conId 为主，首次按"空格→点"找标的 |
| 富途快照的 `updateTimestamp` 跟着盘后/夜盘走，`curPrice` 却冻结在常规时段收盘（实测美东周日 20:52：SPY 时间戳是周日，价格 764.29 = 周五 K 线收盘） | 判断快照价属于哪天不能看时间戳；账户快照只在下一个交易日 04:00（美东）前采用快照价 |
| 开发实例跑全量增量时，轮转每批订阅 90 只，而生产实例的实时订阅占着同一账户的额度（2026-09-14 实测 21/100），批量订阅被拒，开发增量 521 只失败 450 | 生产在跑时，开发实例不要跑全量增量/轮转；要验证 K 线就对单只 `POST /api/bars/backfill/{symbol}` |
| 开发实例运行中跑 `./mvnw ... verify`（包括带 `-Dtrader.integration=true` 的集成测试）会重打 `trader-app/target/trader-app.jar`，正在用它的 JVM 类加载失败；2026-09-14 实测关停时 `NoClassDefFoundError: logback ThrowableProxy`，关停线程死掉、进程卡住不退 | 跑 verify / 集成测试 / 打包前先停开发实例；只跑单测用 `test` 阶段（不打包）。已卡住的只能发信号结束 |
| 池变动钩子触发的实时订阅对账原先**不看 `auto-subscribe`**：开发实例上加减池成员（手工或持仓同步）会让开发实例也订阅实时报价，与生产同时订（第 2 期遗留，2.0.0 修掉） | 钩子改走 `QuoteSubscriptionService.onPoolChanged`：`auto-subscribe=false` 且没有订阅时不对账；新加池变动钩子时别再直接挂 `reconcile` |
| 深度回补会把券商的脏 K 线**重新拉回来**：2026-09-14 把 SPY 改成 BENCHMARK，`POST /api/pool` 自动重新回补 20 年，1.1.0 已订正的 3 根假日脏 K 线又回来了 | 改池角色或加池都会触发深度回补；之后看巡检的 `phantomBars`，按提示 `POST /api/bars/cleanup/phantom` 先试跑再订正 |
| 前端 history 路由在后端没有回退时，直接打开或刷新深链接是 404，但从首页点进去完全正常，页面自测发现不了（2.0.1 前一直如此） | `SpaForwardController` 转发单段路径；新增前端路由保持单段、不含点，`SpaForwardControllerTest` 会核对 |
| 新增 `@Scheduled` 用的配置项只写在 `MarketDataProperties` 的 `@DefaultValue` 上不够——**占位符解析不看记录默认值**。开发实例 `schedule-enabled=false` 不装配调度器，本地全绿、一上生产就起不来 | 新 cron 必须同时写进 jar 内 `application.yml`；`ScheduledPlaceholdersTest` 会守住这条 |
| 盈透 `EClientSocket` 的 `eConnect / isConnected / eDisconnect` 都是 synchronized（javap 核实），SDK 自带的 `eConnect(host,port,id)` 建 socket 与读服务器版本都**没有超时**。端口在监听、对端不应答（隧道本地端口还在、链路已断）时握手一直占着 client 锁；2.0.2 前状态机在自己的锁里调 `close()→isConnected()`，调度线程挂起，状态查询、断开、关停全卡死，直到对端关 socket | 自建 Socket（连接与握手读都带超时）再 `eConnect(Socket, clientId)`；`close()` 先关底层 socket，`eDisconnect` 放 dispatch；`Transport.open/close` 不得等 SDK 的锁，SDK 回调进状态机先转调度线程。`IbkrHandshakeTimeoutTest`（本地黑洞端口）守住 |
| 盘中触发的深度回补 / 轮转会把**当天没收完的 K 线**存下来，当晚增量看"最新日期已到"就不重拉，盘中价被当成收盘价，账户快照也按它估值（2.0.2 前） | 写库截止到已收盘落定的交易日（`SettledCutoff`）；增量的最新日期只认收盘落定后写入的行；巡检 `unsettledBars` 提示 |
| 富途回复在 `futu-dispatch` 上完成；在它上面 `thenCompose` 续发请求，会睡在限流器上（最长 30 秒），心跳回复排不上、被判断线（2.0.2 前历史 K 线翻页如此） | 接续请求一律 `thenComposeAsync` 换线程；`FutuChannel.qotCall` 在 dispatch 线程上直接失败提醒 |
| 2.0.2 起写接口必须带请求头 `X-Trader-Client`（值任意），`Host` 只认回环；手工 curl 不带头是 403 `REQUEST_REJECTED`，旧 Postman 集合的写请求同样被拒 | `curl -X POST -H 'X-Trader-Client: cli' …`；脚本、前端、Postman 集合都已带，升级后重新导入集合 |
| `check-secrets.sh` 在 2.0.2 前**静默漏报**：`${entry%%\|*}` 切在第一个 `\|` 上，IPv4 与明文口令两个模式被切成非法正则（grep 报错被 `2>/dev/null` 吞掉）；带空格的文件名被 xargs 切碎；`--staged` 扫的是工作区 | NUL 分隔清单 + 系统 grep、按最后一个 `\|` 切、模式非法直接报错退出；改扫描规则后用探针文件反证。别换成 `git grep`：它的正则不认 `\b` |
| 富途复权因子的 `fwdA` 只保留 5 位小数（WMT 拆股 1:3 给 0.33333）；合股与分拆可同在一个事件里（HON flag=258 合股 2:1、fwdA=1.09203），DD 2019 只标合股却含分拆（fwdA=2.116 ≠ 3）；分拆当天原始价跳空（DD 2025-11-03 81.65 → 34.69） | 信号判定用结构口径（ARCHITECTURE §18.4）：纯股数变动用 base/ert 精确比例，混合事件价格用 fwdA、成交量只按股数比例；缺比例不予判定 |
| 同一交易日的成交量两次抓取不一致：开发库（后抓）的 2026-09-01 成交量比 futu-trader 缓存大 0.1%~1.2%，TSM 那天的 RVOL 因此 0.80 → 0.81 | 收盘后判定的 RVOL 事后可能变；评估落库要带输入指纹，复算不一致先查 K 线是否被重拉过 |
| 回测"每笔 +0.48R、区间不含 0"是假象：池与持仓是今天挑的赢家，加对照（同标的同年份任意一天入场）后信号入场的超额差值 −0.1%~−0.3% 且不显著 | 评价入场判据必须带同标的同期的随机入场对照，不能只看绝对期望或对 SPY 的超额 |
| 盈透账户汇总订阅**每个客户端最多 2 个**（第 3 个报 322，另一个客户端不受影响，2026-09-19 实测）；常驻订阅之后**逐条推、没有 End、约 3 分钟一批** | 实时账户常驻 1 个 + 每日快照一次性 1 个 = 2，别再加第三处；汇总逐条攒批再发。**用户要求账户数据只给盈透原值、不折算**（净值就用 NetLiquidation，现价用行情 lastPrice，不用市值 ÷ 数量——两者实测不同） |
| 盈透侧上一次的账户汇总订阅**不随我们退订立刻释放**：再次订阅被 322 拒，`money` 取不到、实时账户卡在 WARMING，直到人工断开重连（2026-09-21 实测：934 条逐分钟采样显示网关 03:45 每日重启后 1 分钟即恢复、`money` 正常；10:30 停采、闲置退订后 13:18 再订才撞上，**泄漏在「闲置退订 → 再订」而不是重连**） | 每次订阅前按跨会话保留的上一个汇总请求 id 补发一次取消；收到 322 自动退订重订（3 次）；错误恢复后清掉 `lastError`。见 `IbkrLiveAccount`，3.0.8 修 |
| `reqPnL` 推送对不对**与第几条无关**（09-19 实测）：刚连上时首条只含一只、1 秒后第二条才对；逐只盈亏稳定后再订**只推一条而且是对的**，价格静止不再推；`reqPnLSingle` 的 realized 恒为 Double.MAX_VALUE | **别按"丢首条"处理**（3.0.3 因此当日盈亏一直空着）：用逐只当日 / 浮盈之和核对，对得上才采用（`IbkrLiveAccount.verifyPnl`）；显示仍用 reqPnL 原值。MAX / NaN / 无穷映射成 null；1101 重订前先取消旧订阅 |
| 盈透当日盈亏**不在开盘归零，而在盘后时段结束（美东 20:00）之后翻转基准**（09-20~09-21 两段逐分钟采样实测：整个上午基准都是上一交易日收盘、开盘无跳变；20:02 归零、20:03~20:05 `daily` 恒为 0.00 且逐只盈亏与 `priorClose` 全是 null、浮盈冻结，20:06 起按新收盘重算） | 那 4 分钟是盈透真实状态不是故障，页面照实显示；**别拿行情 `last` 去校验 `dailyPnl`**（盈透用自己的标价、慢一拍，实测 SPY 102.90 vs 69.30，一分钟后才一致），核对只用"逐只之和 vs 账户口径"。详见 ARCHITECTURE §20.4 |
| 盈透同一时刻给同一只股票**三个不同的"价格"**（09-19 GOOG）：行情 lastPrice 346.08、reqAccountUpdates 的 updatePortfolio 市价 346.11（市值 21,458.82）、reqPnLSingle 的 value ÷ 数量 344.41（市值 21,353.42）；reqAccountUpdates 也只按整 3 分钟推，不比账户汇总快 | 与盈透 App 一致的是：价格取行情 lastPrice、前收取行情 CLOSE，市值 / 当日 / 浮盈取 reqPnLSingle（对照 App 截图核过）；别换成 updatePortfolio |
| AI 额度（每日上限含手工、作业内时长）在评估作业里**边评估边调、先到先得**，评估顺序就是分配顺序；3.0.0 按成分股字母序，四巫日（2026-09-18，51 条候选）额度在 G 开头用完，持仓 IBKR 没轮上。另：预算跳过 / 失败也会写分析行并挂到信号上，"挂着分析"不等于"有结论"（3.0.0 账本因此把 31 条无结论算成放行） | `targets()` 保持持仓 → 池 → 池外；判断有无模型意见看分析的 `verdict`（ALLOW / VETO / ABSENT），别看 `aiAnalysisId` 是否为空 |
| 同一份输入模型立场会摇摆（实测 HWM 连调 4 次：NEUTRAL、BULLISH×3）；OpenAI SDK 的类在 core 模块不可见（optional），Mockito 替身不了引用它的类 | 否决要设门槛（非 LOW + ≥2 条核对通过的证据）；core 只认 `VetoClient` 接口，`com.openai.*` 只在 `OpenAiVetoClient` |

## 当前状态

**生产跑 3.0.8**（2026-09-21 美东 18:00 部署；3.0.7 于 09-19 20:01 部署，3.0.6 于 09-19 01:38 部署，审核期间 09-18 晚至 09-19 凌晨先后部署 rc.1～rc.7；第 3 期见 ARCHITECTURE §16，2.0.2 全量审查后的修复见 §17）。
**第 4 期「入场信号」3.0.0（ARCHITECTURE §18）**：判定引擎、对账与回放统计、每日评估与纸面账本、AI 否决、前端信号页；首次实盘评估为美东 2026-09-17 18:10。
**3.0.2（ARCHITECTURE §19、§20）已在生产**：AI 额度按角色、账本 AI 分组、手工分析按钮、前端第 1、2 期，仪表盘改版与实时账户（`GET /api/account/live`，盈透按需订阅）。
**3.0.3（ARCHITECTURE §20.5）已在生产**：实时账户只给盈透原值（净值 NetLiquidation、盈亏 reqPnL、现价盈透行情 lastPrice），不做折算。
**3.0.4（ARCHITECTURE §20.6）已在生产**：持仓与盈透 App 对齐（涨跌、成本、占组合），修当日盈亏可能一直空着。
**3.0.5 已在生产**：仪表盘持仓表精简（去掉成本、市值，涨跌拆成金额与百分比），碎股数量显示到 4 位。
**3.0.6 已在生产**：前端逐页改版（仪表盘、持仓、账户、基本面、入场信号、系统六页）与全市场估值接口 `GET /api/fundamentals/valuations`。
**3.0.7 已在生产**：修标的列表的 `barCount` 恒为 6（改取 `daily_bar` 真实统计，覆盖区间同源）。
**3.0.8 已在生产**：修盈透账户汇总被 322 拒、资金整天取不到（闲置退订 → 再订时旧订阅没退干净；补退订 + 322 限次重订；`lastError` 按流分别清除）。
每一期的设计决策、实测结论与已知边界都在
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) 第 10~19 章，交付清单在 [CHANGELOG.md](CHANGELOG.md)。

- 数据规模：521 只标的、58.8 万根日 K（池/持仓/基准 20 年深度）、3.7 万条复权因子、
  5052 天交易日历（2006 起）、2.5 万期财报、逐日估值快照。
- 跑批：每日增量、估值快照、账户快照（美东 18:00，含持仓同步 HOLDING）、信号评估（18:10，22:00 补偿）、成分股周同步、财报周刷新、当天补偿检查；
  碰撞重试 + SKIPPED 留痕 + `jobs` 健康指标。
- 巡检：`./scripts/check-daily.sh` 一条命令覆盖日线审计、基本面审计、账户审计、信号审计、运行健康（美东 19:00 之后跑）。
- 本机 `config/secrets.yml` 已启用两家网关（隧道 + 开发 client-id）；入库的 `config/application.yml` 仍是 `enabled: false`。
