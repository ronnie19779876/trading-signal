# trading-signal

基于 AI 大模型（OpenAI）的美股量化交易系统。Java 21 + Spring Boot 3.5 + PostgreSQL + Vue 3，Maven 多模块，前后端分离。

两家券商网关各司其职：

| 券商 | 职责 |
| --- | --- |
| **盈透 IBKR** | 持仓账户：每日账户资金、收盘持仓价格与盈亏、交易下单 |
| **富途 Futu** | 跟踪与分析：标普 500 + 纳指 100 全量日 K 线、基本面数据、标的池与持仓的实时行情订阅（不落库）、入场信号与 AI 分析 |

项目分多期推进，先易后难。**当前：第 2 期步骤 1~5 已交付并在生产运行**——
标普 500 + 纳指 100 全量日 K 线（20 年深度给池与持仓）、复权因子与读取层复权、交易日历、
实时报价订阅与推流、基本面（估值快照 / 四类财报 / 公司简介）、两套数据审计与定时跑批可靠性保障。

## 文档

| 文档 | 什么时候看 |
| --- | --- |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 系统长什么样、为什么这么设计：模块与依赖方向、SDK 冲突与解决、配置分层、环境隔离，以及每一期的设计决策与实测结论 |
| [docs/API.md](docs/API.md) | REST 接口定义 |
| [docs/postman/](docs/postman/README.md) | Postman 集合与开发/生产环境变量，可直接导入手动验证 |
| [docs/OPERATIONS.md](docs/OPERATIONS.md) | 克隆后怎么跑起来、怎么打包部署、日常检查、故障排查 |
| [CLAUDE.md](CLAUDE.md) | 每次开工必须知道的：命令、端口分配、边界纪律、坑 |
| [CHANGELOG.md](CHANGELOG.md) | 每个版本交付了什么 |

## 快速开始

```bash
# 0. 前置：JDK 21、Node 20+、能访问 Maven Central 与 npm；数据库经 SSH 隧道可达（见 OPERATIONS §1）
# 1. 安装两个不在 Maven Central 上的 SDK 构件（盈透 TWS API、重定位后的富途 SDK）
./scripts/install-sdks.sh
# 2. 构建（含全部单元测试）
./mvnw clean verify
# 3. 本机配置：复制示例并按需填写（数据库密码留空则从 ~/.pgpass 读取）
cp config/secrets.yml.example config/secrets.yml && chmod 600 config/secrets.yml
# 4. 启动开发实例（:8083），首次启动会在 db_trader_dev 上建表并盖 DEV 标记
./scripts/run-local.sh
curl -s http://127.0.0.1:8083/api/system/info
# 5. 前端开发服务器（:5174，/api 代理到 8083）
cd trader-web && npm install && npm run dev
```

`./scripts/install-git-hooks.sh` 会装上提交前的敏感信息扫描（`scripts/check-secrets.sh`）。

## 仓库布局

```
pom.xml                  父 POM：版本（${revision}）、BOM、插件、模块顺序
trader-common            基础工具：环境枚举、时区时钟、脱敏
trader-domain            领域模型：Broker / Market / Instrument …
trader-gateway-api       券商网关端口（接口）与状态
trader-gateway-ibkr      盈透 TWS API 适配器（com.ib.client.* 只在这里）
trader-gateway-futu      富途 OpenD 适配器（com.futu.openapi.* 只在这里）
trader-storage           PostgreSQL + Flyway + 环境守卫 + ~/.pgpass 密码来源
trader-ai                OpenAI 接入（com.openai.* 只在这里）
trader-core              业务编排（只依赖 gateway-api，不依赖适配器实现）
trader-app               Spring Boot 启动、REST、静态前端、fat jar
trader-web               Vue 3 + Vite + TypeScript + Element Plus
sdk/                     第三方 SDK 供给：tws-api 安装、futu-api-shaded 重定位工程
config/                  本机开发外置配置（secrets.yml 不入库）
deploy/                  生产部署模板：bin / config / systemd
scripts/                 install-sdks / run-local / package / check-secrets / install-git-hooks
docs/                    ARCHITECTURE / API / OPERATIONS
```

## 敏感信息纪律

本仓库公开托管。**账户号、密码、密钥、交易密码 MD5、网关与数据库的主机/端口/client-id 一律不入库**，也不出现在文档与注释里：

- 本机：`config/secrets.yml`（gitignore）；命令行还可走环境变量；数据库密码可留空由 `~/.pgpass` 提供。
- 生产：`deploy/config/trader.env`（gitignore），由 `bin/trader.sh` 在启动前载入。
- 第三方 SDK 的 jar 不入库（`.gitignore` 排除 `*.jar`），由 `scripts/install-sdks.sh` 本地安装。
- `scripts/check-secrets.sh` 在提交前扫描 IP、账户号形态、API key、私钥、明文口令。

## 版本规则

版本号只改父 POM 的 `<revision>` 一处。开发与测试一律 `-SNAPSHOT`，发布时去掉 `-SNAPSHOT`、打 tag `v<版本>`、写 CHANGELOG，随后开发版本按规则递增：

| 变更类型 | 示例 |
| --- | --- |
| BUG 修复 | 1.0.0 → 1.0.1 |
| 既有功能改进完善 | 1.0.0 → 1.1.0 |
| 新增功能 | 1.0.0 → 2.0.0 |

## 环境隔离

开发与测试只连开发库 `db_trader_dev`，生产只连 `db_trader`。应用启动时用库里的 `app_environment` 标记核对声明的 `trader.environment`，不一致拒绝启动；只有全新的空库才会被自动盖章。
