# CHANGELOG

## 1.0.0-SNAPSHOT（开发中）

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
