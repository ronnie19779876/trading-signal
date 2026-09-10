# 运维手册

> 本文不含主机名、IP、端口以外的敏感值；网关与数据库的连接参数只存在于 `config/secrets.yml` / `deploy/config/trader.env`。

## 1. 前置条件

- JDK 21（脚本自动探测：`$JDK21_HOME` → `$JAVA_HOME` → `/usr/libexec/java_home -v 21` → `~/Java/jdk-21*.jdk` → 常见 Linux 路径 → PATH），Node 20+。
- PostgreSQL 18 库 `db_trader_dev`（开发）/ `db_trader`（生产），角色 `trader`。数据库只监听服务器本机，开发机经 SSH 隧道（§1a）把本地端口转发到服务器；密码写在 `~/.pgpass`（`chmod 600`），psql 与应用共用。
- 两家网关（IB Gateway、OpenD）同样跑在服务器上，开发机经隧道访问；第 1 期启用连接层时把 host/port 填进 `config/secrets.yml`。
- Maven 本地仓库：`mvnw`（Maven 3.9.x）读 `~/.m2/settings.xml`；若机器上另有独立安装的 Maven 且改过 `localRepository`，两边要指向同一目录，否则 `install-sdks.sh` 装进的构件另一边找不到。

## 1a. SSH 隧道（开发机 → 服务器）

数据库、两家网关、生产实例都只监听服务器本机，开发机上的一切访问（psql、开发实例连网关、Postman 打生产、`check-daily.sh`）都经同一条 SSH 隧道。主机、SSH 端口、用户名不入库，下面用占位符。

**建立**（用 `autossh`，`brew install autossh`；裸 `ssh -L` 断了不会自愈，表现是 `curl` 挂住而不是连接被拒）：

```bash
autossh -M 0 -f -N \
  -o "ServerAliveInterval=30" -o "ServerAliveCountMax=3" -o "ExitOnForwardFailure=yes" \
  -p <ssh端口> \
  -L 11111:localhost:11111 -L 4001:localhost:4001 -L 5432:localhost:5432 \
  -L 8093:localhost:8093 \
  <用户>@<主机>
```

| 转发 | 用途 |
| --- | --- |
| 11111 | OpenD（富途行情 / 交易） |
| 4001 | IB Gateway 实盘端口 |
| 5432 | PostgreSQL（`db_trader_dev` / `db_trader`） |
| 8093 | 本项目生产实例（Postman、`check-daily.sh`、前端联调） |

兄弟项目的 8090 / 8091 若也要用，一并加到同一条命令里；本机只保留**一条**隧道。

三个 `-o` 参数缺一不可：`-M 0` 关掉 autossh 自带的监控端口，改由 ssh 心跳判活；`ServerAliveInterval` + `ServerAliveCountMax` 让半开连接在 90 秒内被判死、触发重建；`ExitOnForwardFailure=yes` 让端口被占用时 ssh 直接失败，而不是留下一条「已连接但没转发」的假隧道。`-f` 由 autossh 自己处理，并把 `AUTOSSH_GATETIME` 置 0，开机或断网恢复后会一直重试。

**检查**：

```bash
pgrep -fl autossh; for p in 11111 4001 5432 8093; do nc -z localhost $p && echo "$p 通" || echo "$p 不通"; done
curl -s --max-time 5 http://127.0.0.1:8093/api/system/info      # 应回 environment=PROD
```

**改转发列表 / 停隧道**：先杀 autossh 再重起，**不要叠着起第二条**——第二条会因端口被占而反复失败，`pgrep` 里看到多个 autossh 就是这个症状（只有一个真正持有 ssh）。只 `pkill ssh` 没用，autossh 会立刻把它拉起来。

```bash
pkill autossh          # 停；随后按上面的命令重起
```

隧道是手动进程，Mac 重启后要重新执行建立命令。本机 Trader Workstation 桌面版若在跑，它是直连服务器的，与隧道无关。

**开发实例走隧道的坑**：Mac 上开着系统级 SOCKS 代理时 JVM 会自动带 `socksProxyHost`，pgjdbc 连 127.0.0.1 报 `UnknownHostException`；`run-local.sh` 与 IDEA 运行配置已带 `-DsocksNonProxyHosts='localhost|127.*|[::1]'`。

## 2. 克隆后第一次

```bash
./scripts/install-sdks.sh                 # tws-api + futu-api-shaded → 本地仓库
./scripts/install-git-hooks.sh            # 提交前敏感信息扫描
cp config/secrets.yml.example config/secrets.yml && chmod 600 config/secrets.yml
./mvnw clean verify
./mvnw -pl trader-app -am -DskipTests package
./scripts/run-local.sh                    # :8083；首次在 db_trader_dev 建表并盖 DEV 标记
curl -s http://127.0.0.1:8083/actuator/health
curl -s http://127.0.0.1:8083/api/system/info
./scripts/run-local.sh stop
```

前端：`cd trader-web && npm install && npm run dev`（:5174）。

启用网关：在 `config/secrets.yml` 里把 `trader.ibkr.enabled` / `trader.futu.enabled` 置 true 并填 host/port/client-id（隧道时 host 为 127.0.0.1），
重启开发实例后系统页应显示两家 CONNECTED、心跳时刻每 30 秒更新，`gateway_event` 表出现 CONNECTED 记录。

手动验证：导入 `docs/postman/` 下的集合与 `dev` 环境，按 [docs/postman/README.md](postman/README.md) 的顺序点一遍；或 `npx --yes newman run docs/postman/trading-signal.postman_collection.json -e docs/postman/trading-signal.dev.postman_environment.json` 整套跑。

集成测试（对真实网关，只读；连接参数只从环境变量读，缺失即跳过）：

```bash
export TRADER_IBKR_HOST=127.0.0.1 TRADER_IBKR_PORT=<隧道端口> TRADER_IBKR_TEST_CLIENT_ID=91 \
       TRADER_FUTU_HOST=127.0.0.1 TRADER_FUTU_PORT=<隧道端口>
./mvnw -pl trader-app -am verify -Dtrader.integration=true -Dtest='IbkrGatewayIT,FutuGatewayIT,ReconnectIT' -Dsurefire.failIfNoSpecifiedTests=false
```

`ReconnectIT` 会用 client-id 92（测试 id + 1）经本地 TCP 中继连盈透；不要与运行中的实例撞 id。

## 3. 打包与部署

```bash
./scripts/package.sh                      # dist/trading-signal-<版本>-<时间戳>.tar.gz（含前端）
```

服务器约定（部署用户无 sudo，`/opt` 不可写）：解压到 `~/trading-signal-<版本>-<时间戳>/`，用软链 `~/trading-signal` 指向当前版本；配置与日志都在该目录内。

首装：

```bash
scp -P <ssh端口> dist/trading-signal-<版本>-<时间戳>.tar.gz <用户>@<主机>:~/
ssh -p <ssh端口> <用户>@<主机>
mkdir -p ~/trading-signal-<版本>-<时间戳> && tar -C ~/trading-signal-<版本>-<时间戳> --strip-components=1 -xzf ~/trading-signal-<版本>-<时间戳>.tar.gz
ln -sfn ~/trading-signal-<版本>-<时间戳> ~/trading-signal && cd ~/trading-signal
cp config/trader.env.example config/trader.env && chmod 600 config/trader.env && vi config/trader.env   # JAVA_HOME、两家网关 host/port/client-id、enabled
bin/trader.sh start && bin/trader.sh status
```

- 数据库密码：服务器用户的 `~/.pgpass`（600）里有 `localhost:5432:db_trader:trader:<密码>` 时 `TRADER_DB_PASSWORD` 留空即可。
- 首次启动在空库 `db_trader` 上自动建表并盖 PROD 标记；`GET /api/system/info` 应显示 `environment=PROD`、`database.marker=PROD`。
- 发布包默认 `schedule-enabled=true`、`realtime.auto-subscribe=true`，启动后即为生产形态。

升级：解压新版本到新的时间戳目录，把旧目录的 `config/trader.env` 拷过去，`bin/trader.sh stop`（旧）→ 改软链 → `bin/trader.sh start`（新）。不要混用 `bin/trader.sh` 与 systemd。

**升级前必看**（2026-09-09 踩过）：

- 生产 `schedule-enabled=true` 而开发是 false，**调度器只在生产装配**。新增 `@Scheduled` 用到的配置项如果只写在
  `MarketDataProperties` 的 `@DefaultValue` 上、没进 jar 内 `application.yml`，本地怎么跑都正常，一上生产启动直接失败。
  `ScheduledPlaceholdersTest` 现在会守住这条，但改调度相关配置时仍要留意这个开发/生产差异。
- 起不来先看 `logs/console.log` 的第一条 `Application run failed`，配置类问题在那里说得很清楚。
- 旧版本目录不要马上删：回滚就是把软链指回去再 `bin/trader.sh start`。
- **清理旧版本目录前先把 `logs/` 拷出来**：日志在版本目录里，删目录等于把事后追查的依据一起删掉
  （2026-09-10 复查时就发生过前一晚增量的日志已找不到）。`logs/trading-signal.log` 按天滚动保留 14 天，
  `console.log` 是 nohup 重定向、不滚动。
- 本机偶发 `Could not resolve hostname`（macOS 系统解析器坏了，`host` 命令却能解）：
  用 `host <主机名>` 取到地址后按地址连，或重启 mDNSResponder。隧道已建立的连接不受影响。

systemd（需要 sudo，可选）：`systemd/trading-signal.service` 里把 `WorkingDirectory`、`PIDFile`、`ExecStart` 路径改成 `~/trading-signal`（软链）后 `sudo cp` 到 `/etc/systemd/system/`，`daemon-reload`、`enable --now`。之后只用 systemctl 管理。

首轮数据装载（生产空库）：`POST /api/universe/sync` → `POST /api/bars/refresh/universe?count=1000` → `POST /api/bars/rehab/refresh?all=true` → 逐只 `POST /api/pool/{symbol}?role=HOLDING|POOL` → `POST /api/bars/backfill` → `GET /api/bars/audit`。

基本面首轮装载：`POST /api/fundamentals/valuation/refresh`（520 只，几秒）→ `POST /api/fundamentals/financials/refresh?all=true`（520 只四类报表，约 42 分钟）→ `GET /api/fundamentals/audit`。两者都不占订阅与历史 K 线额度，但会占住作业线程，别和轮转类作业排一起。

## 4. 数据库

- 迁移由启动时 Flyway 执行；不启动应用也能建表：`./mvnw -pl trader-storage -am flyway:migrate -Dflyway.url=... -Dflyway.user=trader`（密码走 `PGPASSWORD` 或 `-Dflyway.password`，不要写进命令历史）。
- 环境标记：`SELECT * FROM app_environment;`。错连时应用拒绝启动并打印修复提示。从生产 dump 灌开发库后：`UPDATE app_environment SET name='DEV', stamped_at=now(), note='从生产 dump 灌入后改标记';`
- 密码来源顺序：环境变量 `TRADER_DB_PASSWORD` → `config/secrets.yml` → `~/.pgpass`（日志会打印"已从 ~/.pgpass 读取 …"，不打印密码）。关闭 pgpass 回落：`trader.storage.pgpass.enabled=false`。

## 4a. 行情数据底座（第 2 期·步骤 1）

首次建库后的顺序：`POST /api/universe/sync`（约 1 分钟）→ `POST /api/bars/refresh/universe?count=1000`（约 7 分钟，零额度）→ `POST /api/bars/rehab/refresh?all=true`（约 5 分钟，零额度）→ 把候选加入池 `POST /api/pool/{symbol}`（每只占 1 个历史额度，自动排深度回补）。

- 历史额度：`GET /api/bars/quota`，7 天滚动、预留 10；额度不足时深度回补作业标 PARTIAL，下周由周六的定时作业续补（或手工 `POST /api/bars/backfill`）。
- 定时：发布包 `trader.marketdata.schedule-enabled=true`（每日 17:30 ET 增量、周六 06:30 ET 成分股同步），开发/测试实例一律 false，手工用 `POST /api/bars/increment` 触发。**同一时刻只允许一个实例开启**，且定时只在实例运行时触发。
- 复权口径：读取时算，默认 `factor-mode=PER_EVENT`（实测与富途前复权一致）；不要改成 CUMULATIVE。
- 订阅额度：轮转每批 90 只，跑批期间富途订阅额度接近用满，此时不要在同一 OpenD 上做别的订阅。

## 4b. 实时报价（第 2 期·步骤 2）

- 生产实例 `trader.marketdata.realtime.auto-subscribe=true`：富途连上即订阅池与持仓；开发机 false，需要时 `POST /api/quotes/subscriptions/reconcile`。**不要让两个实例同时订阅**（额度按标的 × 类型计，两份就超 100）。
- 巡检：`GET /api/quotes/status` 的 `subscribed` 应等于 `desired`，`pushesLastMinute` 在交易时段（含盘前盘后）大于 0，`lastError` 为空。
- 全量轮转期间实时订阅自动暂停、结束后恢复（`pause-during-refresh`）；手工 `pause` 后记得 `resume`。
- 报价不落库；重启后缓存为空，首推后恢复。

## 5. 日常检查

> 定时作业不再静默丢失：碰撞时每 5 分钟重试、最多半小时，仍失败会写一行 `SKIPPED`；
> 美东 21:00 还有一次当天补偿检查，缺 K 线或估值就补跑（触发方式记 `CATCHUP`）。
> 补偿必须早于次日盘前——券商收盘后冻结当前价，过了盘前就取不到当日口径的估值快照了。


**收盘后必做**（美东 17:30 增量跑完后，约北京时间次日 06:00）：

```bash
./scripts/check-daily.sh http://127.0.0.1:8093        # 在服务器上跑；本机经隧道则改成隧道端口
```

本机执行需要 §1a 的隧道把 8093 转发到 127.0.0.1。

`historyGaps` 报警时：最近 90 天的缺口先重跑增量（`POST /api/bars/increment`）；补不回来的多是券商缺数。
全历史深扫用 `GET /api/bars/gaps`，已知长期缺口有 NBIS（停牌 664 天）与 SPY（券商缺数 26 天），两者都补不回来。

它依次调三处，一条命令覆盖全部：

1. `GET /api/bars/audit` 日线审计：完整性、字段合理性、前收连续性、复权新鲜度、同步错误、增量作业、日历覆盖、对照日历的近期缺口、网关。
2. `GET /api/fundamentals/audit` 基本面审计：估值完整性与合理性、财报陈旧度、估值作业。
3. `GET /actuator/health` 运行健康：`jobs` 组件在任一定时作业 FAILED / SKIPPED / 逾期时降级，`gateways` 在网关掉线时降级。

`ok=false` 时看 `checks` 里失败项与样本；退出码 0 全通过 / 1 有关键项失败或健康降级 / 2 接口不可达。假日（如劳工节）不带参数跑会自动审计上一个交易日；显式传休市日则回"当天休市"并判通过。

- `GET /api/gateways`：两家 CONNECTED，`lastHeartbeatAt` 在 1 分钟内，`reconnectAttempts` 为 0；盈透 facts 里各 `farm.*` 为 OK 或 INACTIVE（INACTIVE 正常）。
- `GET /api/gateways/events?limit=20`：盈透每日自动重启会留下一对 DISCONNECTED / RECONNECTED，属正常；频繁出现则查隧道或网关。
- `GET /actuator/health` 为 UP（DEGRADED 表示有网关未连上，看 `components.gateways`）；`GET /api/system/info` 的 `environment` 与所在机器一致、`database.marker` 与之一致。
- 版本：`/api/system/info` 的 `version` / `buildTime` 与发布包一致。
- 行情：`GET /api/bars/coverage` 的 `latest` 应为最近一个已收盘交易日，`withErrors` 为 0，`rehabCovered` 等于 `universeSize`；`GET /api/jobs` 最近的 DAILY_INCREMENT 为 OK。
- 数据质量核查 SQL（只读，任一 psql 可跑）：每只最新交易日是否一致、每只根数分布、以某只标的日期集合为参照的缺口数、`last_close` 与上一根 `close` 是否相等（不等即漏日）、`|change_rate|>50` 的行应都能对应拆股。

## 6. 故障排查

| 现象 | 原因 | 处置 |
| --- | --- | --- |
| 启动报 `未声明 trader.environment` | 外置配置没生效 | 确认启动参数 `--spring.config.additional-location=file:./config/` 且工作目录是仓库根/部署目录 |
| 启动报 `环境不匹配` | JDBC URL 指错了库 | 核对 `spring.datasource.url`；开发只连 `db_trader_dev` |
| 启动报 `拒绝自动盖章` | 库里已有表但没有标记 | 确认库没指错后手工 `INSERT INTO app_environment ...` |
| `Failed to determine a suitable driver class` | 外置配置里 `trader.storage.enabled=true` 但没有 `spring.autoconfigure.exclude: []` | 补上 |
| `UnknownHostException: 127.0.0.1`（psql 正常） | 本机 SOCKS 代理劫持了 JDBC | 启动参数加 `-DsocksNonProxyHosts='localhost|127.*|[::1]'`（脚本与 IDEA 配置已带） |
| `NoClassDefFoundError: com/ib/client/...` 或 `com/futu/openapi/...` | fat jar 缺 SDK | 检查 `trader-app/pom.xml` 的 runtime 依赖是否被删 |
| `NoSuchMethodError ... Descriptors$FileDescriptor.internalBuildGeneratedFileFrom` | 有人把 `com.futunn.openapi:futu-api` 直接加进了依赖 | 只能依赖 `futu-api-shaded` |
| `./mvnw` 找不到 tws-api | wrapper 的 Maven 与安装 SDK 时用的本地仓库不同 | 统一 `~/.m2/settings.xml` 的 `localRepository`，或重跑 `install-sdks.sh` |
| 盈透状态 RECONNECTING，facts 有 `326 client-id 已被占用` | 另一个实例用了同一个 client-id | 给每个实例分配独立 id（开发 12 / 生产 2 / 临时 91~99） |
| 盈透每天固定时间 DISCONNECTED → RECONNECTED | IB Gateway 每日自动重启 | 正常；几分钟内自动恢复。每周需人工 2FA 重新登录网关 |
| 盈透 `detail` 为「配置的 trader.ibkr.account 不在网关的受管账户列表里」且状态 ERROR | 账户号配错或登录了别的用户名 | 核对后重启实例 |
| 富途 CONNECTED 但 detail「OpenD 状态为 …」/「行情未登录」 | OpenD 未完成登录、需要验证码或未同意协议 | 到服务器上看 OpenD 日志，`relogin` / 输入验证码 |
| 富途状态 RECONNECTING 且 detail 含「连接 OpenD 失败」 | OpenD 未运行或隧道未建 | 检查 OpenD 进程与隧道 |
| 作业 FAILED，摘要含「限频」或「等待超过上限」 | 同一 OpenD 上有别的程序在频繁调用 | 错开时间再跑；限频阈值可在 `trader.futu.limits` 调低 |
| 深度回补 PARTIAL，摘要含「历史额度用尽」 | 7 天 100 只额度用完 | 正常；下周六自动续补 |
| 成分股同步 PARTIAL，摘要含「来源失败」 | Wikipedia 不可达或页面结构变了 | 保留旧成分；用 `POST /api/universe/import` 导入 CSV 兜底 |
| `/api/quotes/status` 的 `subscribed` < `desired` 且 `lastError` 含「额度」 | 订阅额度被别的实例或 App 占用 | `getSubInfo` 看全部连接；关掉多余实例或减小池 |
| 实时报价盘前盘后不动 | 看的是 `rthPrice`（非常规时段冻结） | 用 `price`（有效价）或 preMarket/afterMarket 字段 |
