# 第三方 SDK 供给

主工程依赖两个**不在 Maven Central** 上的构件，克隆仓库后先跑一次：

```bash
./scripts/install-sdks.sh
```

| 构件 | 来源 | 安装方式 |
| --- | --- | --- |
| `com.interactivebrokers:tws-api:<tws-api.version>` | 盈透官方下载页 https://interactivebrokers.github.io/ 的 `twsapi_macunix.<版本>.zip`，jar 在 `IBJts/source/JavaClient/TwsApi.jar` | 脚本自动下载到 `sdk/tws-api/`（已 gitignore）并 `mvn install:install-file`；离线时把 jar 放到 `sdk/tws-api/TwsApi.jar` 再跑脚本 |
| `org.jdkxx.trader:futu-api-shaded:<版本>` | 本目录 `futu-api-shaded/pom.xml`，从 Maven Central 拉 `com.futunn.openapi:futu-api` 后重定位 protobuf | `mvn -f sdk/futu-api-shaded/pom.xml install` |

- 盈透 SDK 的许可证不允许公开再分发，**jar 永远不入库**（根 `.gitignore` 排除了 `*.jar`）。
- 版本号的唯一来源是根 `pom.xml` 的 `tws-api.version` / `futu-api-shaded.version`，脚本从那里读取。
- 升级富途 SDK：改 `futu-api-shaded/pom.xml` 的两处版本 → 重跑脚本 → 改根 `pom.xml` 的 `futu-api-shaded.version`。
- 升级盈透 SDK：先确认新版内置 protobuf 生成代码要求的 protobuf-java 版本（10.35+ 据报需要 5.x），同步改 `protobuf-java.version`。
