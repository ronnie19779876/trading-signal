# 运维手册

> 本文不含主机名、IP、端口以外的敏感值；网关与数据库的连接参数只存在于 `config/secrets.yml` / `deploy/config/trader.env`。

## 1. 前置条件

- JDK 21（脚本自动探测：`$JDK21_HOME` → `$JAVA_HOME` → `/usr/libexec/java_home -v 21` → `~/Java/jdk-21*.jdk` → 常见 Linux 路径 → PATH），Node 20+。
- PostgreSQL 18 库 `db_trader_dev`（开发）/ `db_trader`（生产），角色 `trader`。数据库只监听服务器本机，开发机经 SSH 隧道把本地端口转发到服务器；密码写在 `~/.pgpass`（`chmod 600`），psql 与应用共用。
- 两家网关（IB Gateway、OpenD）同样跑在服务器上，开发机经隧道访问；第 1 期启用连接层时把 host/port 填进 `config/secrets.yml`。
- Maven 本地仓库：`mvnw`（Maven 3.9.x）读 `~/.m2/settings.xml`；若机器上另有独立安装的 Maven 且改过 `localRepository`，两边要指向同一目录，否则 `install-sdks.sh` 装进的构件另一边找不到。

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
./scripts/package.sh                      # dist/trading-signal-<版本>-<时间戳>.tar.gz
```

服务器（首装）：

```bash
sudo mkdir -p /opt/trading-signal && sudo tar -C /opt -xzf trading-signal-<版本>-<时间戳>.tar.gz --strip-components=1 -C /opt/trading-signal
cd /opt/trading-signal
cp config/trader.env.example config/trader.env && chmod 600 config/trader.env && vi config/trader.env
bin/trader.sh start && bin/trader.sh status
sudo cp systemd/trading-signal.service /etc/systemd/system/   # 改 User/Group/WorkingDirectory 后 enable --now
```

升级：只替换 `lib/` 与 `bin/`，**保留服务器上的 `config/trader.env` 与 `config/application.yml` 的本地改动**；用 `systemctl restart trading-signal`，不要混用 `bin/trader.sh` 与 systemd。

停止方式：`bin/trader.sh stop` 调 `POST /actuator/shutdown`（优雅关闭），不发信号；systemd 的 `TimeoutStopSec` 是兜底。

## 4. 数据库

- 迁移由启动时 Flyway 执行；不启动应用也能建表：`./mvnw -pl trader-storage -am flyway:migrate -Dflyway.url=... -Dflyway.user=trader`（密码走 `PGPASSWORD` 或 `-Dflyway.password`，不要写进命令历史）。
- 环境标记：`SELECT * FROM app_environment;`。错连时应用拒绝启动并打印修复提示。从生产 dump 灌开发库后：`UPDATE app_environment SET name='DEV', stamped_at=now(), note='从生产 dump 灌入后改标记';`
- 密码来源顺序：环境变量 `TRADER_DB_PASSWORD` → `config/secrets.yml` → `~/.pgpass`（日志会打印"已从 ~/.pgpass 读取 …"，不打印密码）。关闭 pgpass 回落：`trader.storage.pgpass.enabled=false`。

## 5. 日常检查

- `GET /api/gateways`：两家 CONNECTED，`lastHeartbeatAt` 在 1 分钟内，`reconnectAttempts` 为 0；盈透 facts 里各 `farm.*` 为 OK 或 INACTIVE（INACTIVE 正常）。
- `GET /api/gateways/events?limit=20`：盈透每日自动重启会留下一对 DISCONNECTED / RECONNECTED，属正常；频繁出现则查隧道或网关。
- `GET /actuator/health` 为 UP（DEGRADED 表示有网关未连上，看 `components.gateways`）；`GET /api/system/info` 的 `environment` 与所在机器一致、`database.marker` 与之一致。
- 版本：`/api/system/info` 的 `version` / `buildTime` 与发布包一致。

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
