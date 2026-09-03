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

## Actuator

- `GET /actuator/health` — `{"status":"UP"}`，含各组件明细。
- `GET /actuator/info` — build-info（版本、构建时间）。
- `POST /actuator/shutdown` — 仅本机开发与生产外置配置开启，供 `run-local.sh stop` / `bin/trader.sh stop` 使用。
