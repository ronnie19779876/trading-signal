# REST 接口

所有接口同源提供，无鉴权（只监听回环地址，外部访问走 SSH 隧道）。时间一律 ISO-8601 UTC。

## GET /api/system/info

系统信息：版本、环境、数据库、两家网关状态、AI 配置状态。不包含主机、端口、账户号、密钥。

```json
{
  "application": "trading-signal",
  "version": "1.0.0-SNAPSHOT",
  "buildTime": "2026-09-03T01:40:00Z",
  "environment": "DEV",
  "serverTime": "2026-09-03T01:45:12.345Z",
  "database": { "enabled": true, "database": "db_trader_dev", "serverVersion": "18.6", "marker": "DEV", "detail": "OK" },
  "gateways": [
    { "broker": "IBKR", "displayName": "盈透", "role": "持仓账户：资金、持仓盈亏、下单", "state": "DISABLED", "healthy": true, "detail": "未启用（trader.ibkr.enabled=false）", "checkedAt": "..." },
    { "broker": "FUTU", "displayName": "富途", "role": "跟踪与分析：行情、基本面、实时订阅、信号", "state": "DISABLED", "healthy": true, "detail": "...", "checkedAt": "..." }
  ],
  "ai": { "configured": false, "model": "gpt-5.6-sol", "detail": "未配置 trader.ai.api-key，调用模型时会失败" }
}
```

| 字段 | 说明 |
| --- | --- |
| `environment` | 外置配置声明的环境；未声明时为 `未声明` |
| `database.enabled` | `trader.storage.enabled`；为 false 时其余字段为 null |
| `database.marker` | 库内 `app_environment` 标记，应与 `environment` 一致 |
| `gateways[].state` | `DISABLED / DISCONNECTED / CONNECTING / CONNECTED / ERROR` |
| `gateways[].healthy` | `CONNECTED` 或 `DISABLED` 为 true |
| `ai.configured` | 是否配置了 API key（不返回 key） |

## 网关（第 1 期）

| 接口 | 说明 |
| --- | --- |
| `GET /api/gateways` | 两家网关的状态视图数组（字段见下） |
| `GET /api/gateways/{broker}` | 单个，`broker` 为 `ibkr` / `futu`（不区分大小写） |
| `POST /api/gateways/{broker}/connect` | 手工发起连接（未启用 → 400） |
| `POST /api/gateways/{broker}/disconnect` | 手工断开并停止重连 |
| `GET /api/gateways/{broker}/accounts` | 账户列表，账户号脱敏；未连接 → 503 |
| `GET /api/gateways/ibkr/instruments?symbol=AAPL` | 合约明细列表，查无此标的返回 `[]`；富途暂不支持 → 400 |
| `GET /api/gateways/events?limit=50` | 最近连接事件（存储未启用时为 `[]`） |

状态视图：

```json
{
  "broker": "IBKR", "displayName": "盈透", "role": "持仓账户：资金、持仓盈亏、下单",
  "enabled": true, "autoConnect": true,
  "state": "CONNECTED", "healthy": true, "detail": "已连接",
  "checkedAt": "…", "connectedSince": "…", "lastHeartbeatAt": "…", "reconnectAttempts": 0,
  "facts": { "accounts": "1", "serverVersion": "223", "farm.usfarm": "OK", "connectivity": "RESTORED" }
}
```

| 字段 | 说明 |
| --- | --- |
| `state` | `DISABLED / DISCONNECTED / CONNECTING / CONNECTED / RECONNECTING / ERROR` |
| `detail` | 人类可读说明，重连时含"N 秒后第 k 次重连" |
| `lastHeartbeatAt` | 最近一次心跳成功时刻（盈透 reqCurrentTime / 富途 getGlobalState） |
| `facts` | 可公开事实；盈透：serverVersion、accounts、nextOrderId、farm.*、connectivity；富途：opendVersion、qotLogined、trdLogined、programStatus、market.US/HK、channel.qot/trd |

账户视图：`{ "broker": "FUTU", "maskedId": "12*****", "kind": "LIVE|PAPER", "markets": ["HK","US"] }`。

事件视图：`{ "id": 1, "broker": "IBKR", "event": "CONNECTED|RECONNECTED|DISCONNECTED|ERROR", "detail": "…", "occurredAt": "…" }`。

错误响应统一为 `{ "code": "...", "message": "..." }`：`PARAM_INVALID` 400、`GATEWAY_NOT_CONNECTED` 503、`GATEWAY_TIMEOUT` 504、`GATEWAY_REJECTED` 502。

## Actuator

- `GET /actuator/health` — `{"status":"UP"}`，含各组件明细；组件 `gateways` 在有网关启用但未连接时为 `DEGRADED`，总状态随之为 `DEGRADED`，HTTP 仍是 200。
- `GET /actuator/info` — build-info（版本、构建时间）。
- `POST /actuator/shutdown` — 仅本机开发与生产外置配置开启，供 `run-local.sh stop` / `bin/trader.sh stop` 使用。
