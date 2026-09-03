# CHANGELOG

## 1.0.0-SNAPSHOT（开发中）

### 第 0 期：项目骨架（2026-09-03）

- Maven 多模块工程 `org.jdkxx.trader:trader`，`${revision}` + flatten 一处改版本，Maven Wrapper 3.9.16。
- 九个后端模块与依赖方向：`app → { core, gateway-ibkr, gateway-futu }`，`core → { gateway-api, storage, ai }`；三个供应商 SDK 各锁在一个模块。
- 富途 SDK 与盈透 SDK 的 protobuf 冲突：以 `sdk/futu-api-shaded`（protobuf 重定位）解决，`scripts/install-sdks.sh` 一键安装两个非 Central 构件。
- 配置分层：jar 内环境无关默认值；`config/`（开发）与 `deploy/config/`（生产）外置；敏感项只在 gitignore 文件；数据库密码可由 `~/.pgpass` 提供。
- 环境隔离：`trader.environment` 必填，`EnvironmentGuard` 在 Flyway 迁移后比对库内 `app_environment` 标记，只有全新空库才自动盖章。
- `GET /api/system/info`、`/actuator/health|info`，Vue 3 系统信息页（同源打进 jar）。
- 脚本：`run-local.sh`、`package.sh`、`check-secrets.sh`（可装成 pre-commit）、`install-git-hooks.sh`；部署模板 `deploy/bin/trader.sh` 与 systemd 单元。
