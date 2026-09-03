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
```

- 脚本自己探测 JDK 21（`scripts/lib/jdk.sh`），不依赖 `JAVA_HOME`；直接跑 `./mvnw` 时非交互 shell 可能没有 `JAVA_HOME`，需要显式指定 JDK 21。
- `mvnw` 使用 Maven 3.9.x；本地仓库位置由 `~/.m2/settings.xml` 决定，必须与安装 SDK 时用的同一个。
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

## 当前状态

- **第 0 期骨架已交付**（1.0.0-SNAPSHOT）：多模块工程、配置分层、`EnvironmentGuard`、`/api/system/info` 与 `/actuator/health`、Vue 系统页、脚本与部署模板。
- 两家网关只有配置校验与状态报告，`trader.ibkr.enabled` / `trader.futu.enabled` 默认 false。
- **下一期（第 1 期）：网关接入层**——连接管理、断线重连、健康探测、请求-回调关联、限频闸门。开工前先出设计。
