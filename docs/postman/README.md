# Postman 集合

| 文件 | 用途 |
| --- | --- |
| `trading-signal.postman_collection.json` | 全部 REST 接口，每个请求带断言（Tests 页签可看结果） |
| `trading-signal.dev.postman_environment.json` | 本机开发实例：`baseUrl=http://127.0.0.1:8083` |
| `trading-signal.prod.postman_environment.json` | 生产实例（经 SSH 隧道后的本地端口，默认 8093） |
| `build_collection.py` | 生成器。接口只维护在脚本的 `ENDPOINTS` 里，改完重跑；**JSON 不要手改** |

导入：Postman → Import → 选中上面三个 JSON（或整个目录）→ 右上角切换环境为 `trading-signal dev`。

环境变量：`baseUrl`、`broker`（`ibkr` / `futu`，切换后「单个网关状态 / 账户列表 / 手工断开 / 手工连接」跟着变）、`symbol`（合约查询）。

第 1 期手动验证顺序（先 `./scripts/run-local.sh` 起开发实例，`config/secrets.yml` 里两家网关 `enabled: true`）：

1. 系统信息 → 环境 DEV、数据库标记 DEV、两家网关 CONNECTED。
2. 网关状态列表 → 心跳时刻在跳（隔 30 秒再发一次对比 `lastHeartbeatAt`），facts 里有 serverVersion / opendVersion。
3. 账户列表（脱敏）→ `broker` 分别切 `ibkr`、`futu`，账户号形如 `U1*****`。
4. 合约查询（盈透）→ AAPL 有 conId 与 minTick；查无此标的返回 `[]`；富途返回 400。
5. 手工断开 → 网关状态列表变 DISCONNECTED，健康检查 DEGRADED（HTTP 200）；手工连接 → 几秒后回到 CONNECTED。
6. 最近连接事件 → 能看到刚才的 DISCONNECTED / RECONNECTED。

命令行整套跑一遍（需 Node）：

```bash
npx --yes newman run docs/postman/trading-signal.postman_collection.json -e docs/postman/trading-signal.dev.postman_environment.json
```

## 文件被 Postman 客户端改写了怎么办

新版 Postman 客户端接管目录后会把 JSON 集合拆成目录树、把环境变量改成 yaml，
并删掉原来的三个 JSON。它们是 `build_collection.py` 的生成物，重跑即可恢复：

```bash
python3 docs/postman/build_collection.py
```

客户端产物（`.postman/`、`postman/`、`trading-signal/`、`*.environment.yaml`）已在 `.gitignore` 里排除，
集合始终以 JSON 为准。

## 注意：别对生产整集跑 newman

集合里有 POST / DELETE，会改池、写库、触发真实跑批（成分股同步、日历回补、估值与财报刷新等）或断开网关。
2.0.2 起它们集中在每组的「⚠ 写操作」子文件夹里，GET 留在组内；prod 环境的 `symbol` 默认留空，要用时手填。
要验证集合可用性就对开发实例跑（`-e ...dev...`，先启动 `./scripts/run-local.sh`），对生产只跑各组的 GET。

## 请求头 X-Trader-Client

2.0.2 起服务端对缺 `X-Trader-Client` 头的写请求返回 403（挡本机浏览器的跨站 POST），`Host` 也只接受回环地址。
集合里每个请求都已带这个头。**升级到 2.0.2 后要重新导入集合**，旧集合里的写请求会被拒。
