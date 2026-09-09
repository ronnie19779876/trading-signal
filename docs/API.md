# REST 接口

可导入的 Postman 集合与环境见 [postman/](postman/README.md)。

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

## 行情数据底座（第 2 期·步骤 1）

只在 `trader.storage.enabled=true` 时存在。跑批接口都是异步：立即返回 `{ "jobId": n }`，进度看 `GET /api/jobs`；同一时刻只跑一个作业，冲突 → 409 `STATE_CONFLICT`。

| 接口 | 说明 |
| --- | --- |
| `POST /api/universe/sync` | 成分股同步作业：Wikipedia 标普 500 + 纳指 100 → instrument / index_constituent（since/until），SPY 交叉核对，富途静态信息解析 |
| `POST /api/universe/import`（`text/plain`，每行 `index_code,symbol[,name]`） | CSV 导入兜底，同步返回各指数的新增/退出计数 |
| `GET /api/universe?index=SP500|NDX100&role=POOL|HOLDING` | 标的列表（含所属指数、行业、池角色、K 线覆盖与深度、最近错误） |
| `GET /api/universe/{symbol}` | 单个标的；不存在 → 404 |
| `GET /api/pool` / `POST /api/pool/{symbol}?role=POOL|HOLDING&note=` / `DELETE /api/pool/{symbol}` | 标的池；库里没有的代码（ETF、非成分股 ADR）先向富途解析并建档，富途不认识 → 404；加入后自动排深度回补作业（无法自动时返回提示）；池满 → 409 |
| `POST /api/bars/refresh/universe?count=1000` | 全量轮转拉 K 线（零历史额度；1000 首拉 / 10 增量） |
| `POST /api/bars/backfill/{symbol}` / `POST /api/bars/backfill` | 深度回补一只 / 所有待补的池与持仓（占历史额度，额度守卫） |
| `POST /api/bars/increment` | 每日增量：交易日历 → 缺口补齐 → 复权因子刷新（池/持仓每日，全量 7 天到期的） |
| `POST /api/bars/rehab/refresh?all=false` | 复权因子刷新作业：all=true 全量（约 5 分钟）；否则池/持仓 + 到期的 |
| `GET /api/bars/{symbol}?from&to&adjust=none|forward|backward` | K 线（默认最近 90 天）；复权在读取层计算 |
| `GET /api/bars/{symbol}/rehab` | 复权因子 |
| `GET /api/bars/audit?date=` | 日线数据审计（默认最近应有收盘 K 的交易日）：传入的日期若在日历里是休市日，只回一条 `calendar` 检查并判通过；completeness / sanity / continuity 为关键项，rehab / syncErrors / incrementJob / gateway 为提示项；`ok` = 关键项全过 |
| `GET /api/bars/coverage` | 行数/标的数/最早最新、全量/池/持仓规模、已覆盖数、复权因子覆盖数、未解析数、错误数、历史额度、运行中的作业 |
| `GET /api/bars/quota` | 历史额度（7 天滚动） |
| `GET /api/jobs?limit=` / `GET /api/jobs/{id}` / `POST /api/jobs/cancel` | 作业记录与取消（在下一批边界停下） |

## 基本面（第 2 期·步骤 3）

估值快照全量每交易日一次，财报只做池与持仓、每周一次。两者都不占订阅额度与历史 K 线额度。

| 方法与路径 | 说明 |
| --- | --- |
| `GET /api/fundamentals/{symbol}` | 概览：最新估值 + 最近 4 期主要指标 + 公司简介 |
| `GET /api/fundamentals/{symbol}/valuation?from&to` | 估值时间序列（默认最近 90 天）。**亏损股的市盈率市净率为负是真实数据** |
| `GET /api/fundamentals/{symbol}/reports?statement&limit` | 财报期次与数据项；statement 取 `income`/`balance_sheet`/`cash_flow`/`main_index` |
| `GET /api/fundamentals/coverage` | 覆盖：最新估值日期、当天有估值的只数、财报期数、池里有财报的只数 |
| `GET /api/fundamentals/audit?date=` | 基本面审计；休市日直接判过 |
| `POST /api/fundamentals/valuation/refresh` | 估值快照作业（全量 ∪ 池 ∪ 持仓，一次 400 只） |
| `POST /api/fundamentals/financials/refresh` | 财报作业（池 + 持仓，四类报表，约 80 秒） |

读这些数据前要知道的三件事：

- ETF 没有市盈率市净率，个股没有净值；两套口径共用一张表，缺的字段是 `null`。富途对多数美股 ETF 不给净值。
- 年报与四季报的**期末可能是同一天**，靠 `periodText`（如 `2026/FY` 与 `2026/Q4`）区分。
- `fiscalYear` 可能领先自然年，排序与取"最近一期"一律用 `periodEnd`。


K 线字段：`tradeDate, open, high, low, close, lastClose, volume, turnover, turnoverRate(小数), changeRate(百分数), pe, blank`。

## 实时报价（第 2 期·步骤 2，不落库）

| 接口 | 说明 |
| --- | --- |
| `GET /api/quotes` | 缓存里的全部最新报价 |
| `GET /api/quotes/{symbol}` | 单个；未订阅或尚未收到推送 → 404 |
| `GET /api/quotes/stream` | SSE：`event: quotes`（数组，只含上一帧后变过的）每秒最多一帧；`event: status` 每 15 秒；连接 30 分钟超时，客户端自动重连 |
| `GET /api/quotes/status` | enabled / paused / desired / subscribed / deferredUnsubscribe / quota（usedQuota、remainQuota、byType）/ cached / totalPushes / pushesLastMinute / lastPushAt / streamClients / lastError |
| `POST /api/quotes/subscriptions/reconcile` | 对账：期望 = 池 ∪ 持仓；返回 desired / subscribed / added / removed / deferred / error |
| `POST /api/quotes/subscriptions/pause` / `resume` | 暂停（反订阅全部、清缓存）/ 恢复 |

报价字段：`instrument, session(PRE|RTH|AFTER|OVERNIGHT|CLOSED), price, change, changeRate(百分数), open, high, low, rthPrice, lastClose, volume, turnover, preMarket{price,change,changeRate,volume}, afterMarket{…}, overnight{…}, quoteTime, receivedAt, suspended`。`price/change/changeRate` 是按时段取的有效价。

## Actuator

- `GET /actuator/health` — `{"status":"UP"}`，含各组件明细；组件 `gateways` 在有网关启用但未连接时为 `DEGRADED`，总状态随之为 `DEGRADED`，HTTP 仍是 200。
- `GET /actuator/info` — build-info（版本、构建时间）。
- `POST /actuator/shutdown` — 仅本机开发与生产外置配置开启，供 `run-local.sh stop` / `bin/trader.sh stop` 使用。
