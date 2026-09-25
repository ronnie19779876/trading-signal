# REST 接口

可导入的 Postman 集合与环境见 [postman/](postman/README.md)。

所有接口同源提供，无鉴权（只监听回环地址，外部访问走 SSH 隧道）。时间一律 ISO-8601 UTC。

回环地址挡不住本机浏览器（跨站 POST、DNS rebinding），所以另有两条防护（2.0.2 起）：

- `Host` 的主机名必须是 `localhost` / `127.0.0.1` / `[::1]`（端口不限，隧道的本地端口可以与服务端口不同），否则 403 `REQUEST_REJECTED`；
- 非 GET / HEAD / OPTIONS 的请求必须带请求头 `X-Trader-Client`（值任意：前端 `web`、脚本 `script`、Postman 集合 `postman`），否则 403。
  手工调写接口：`curl -X POST -H 'X-Trader-Client: cli' http://127.0.0.1:8083/api/...`。

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
| `gateways[].state` | `DISABLED / DISCONNECTED / CONNECTING / CONNECTED / RECONNECTING / ERROR` |
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

错误响应统一为 `{ "code": "...", "message": "..." }`：`PARAM_INVALID` 400、`REQUEST_REJECTED` 403（本机请求防护，见开头）、`NOT_FOUND` 404、`STATE_CONFLICT` 409、`GATEWAY_REJECTED` 502、`GATEWAY_NOT_CONNECTED` 503、`GATEWAY_TIMEOUT` 504。

## 行情数据底座（第 2 期·步骤 1）

只在 `trader.storage.enabled=true` 时存在。跑批接口都是异步：立即返回 `{ "jobId": n }`，进度看 `GET /api/jobs`；同一时刻只跑一个作业，冲突 → 409 `STATE_CONFLICT`。

| 接口 | 说明 |
| --- | --- |
| `POST /api/universe/sync?force=false` | 成分股同步作业：Wikipedia 标普 500 + 纳指 100 → instrument / index_constituent（since/until），SPY 交叉核对，富途静态信息解析。**单次退出数守护**：某指数的退出数超过 `max(5, 现有成员 5%)` 时**整个指数跳过、一条都不改**，作业记 PARTIAL 并写明原因；另一指数照常。确认来源无误（例如纳指 12 月年度重构）后带 `force=true` 重跑 |
| `POST /api/universe/import?force=false`（`text/plain`，每行 `index_code,symbol[,name]`） | CSV 导入兜底，同步返回各指数的新增/退出计数。**按指数整体替换**：清单里没有的现任成分股记为退出，所以要给该指数的完整清单。同样受上面的退出数守护——给残缺清单会被整体挡下，`error` 里写明退出数与阈值 |
| `GET /api/universe?index=SP500|NDX100&role=POOL|HOLDING|BENCHMARK` | 标的列表（含所属指数、行业、池角色、K 线覆盖与深度、最近错误）。`barCount` 与 `earliest` / `latest` 取自 `daily_bar` 的真实统计（3.0.7 修；此前取同步状态里的最近一次写入条数，所有标的恒为 6） |
| `GET /api/universe/{symbol}` | 单个标的；不存在 → 404 |
| `GET /api/pool` / `POST /api/pool/{symbol}?role=POOL|BENCHMARK&note=` / `DELETE /api/pool/{symbol}` | 标的池；库里没有的代码（ETF、非成分股 ADR）先向富途解析并建档，富途不认识 → 404；加入后自动排深度回补作业（无法自动时返回提示）；池满 → 409；`role=HOLDING` → 409（HOLDING 由盈透持仓自动维护，见"账户与持仓"） |
| `POST /api/bars/refresh/universe?count=1000` | 全量轮转拉 K 线（零历史额度；1000 首拉 / 10 增量） |
| `POST /api/bars/backfill/{symbol}` / `POST /api/bars/backfill` | 深度回补一只 / 所有待补的池与持仓（占历史额度，额度守卫）。轮转与回补都只写到已收盘落定的交易日，盘中触发时当天那根不写 |
| `POST /api/bars/increment` | 每日增量：交易日历 → 缺口补齐 → 复权因子刷新（池/持仓每日；全量 7 天到期的按最久未刷优先，每次最多全量的 1/`rehab-spread-days`） |
| `POST /api/bars/rehab/refresh?all=false` | 复权因子刷新作业：all=true 全量（约 5 分钟）；否则池/持仓 + 到期的（同样限量，摘要里写明顺延几只） |
| `GET /api/bars/{symbol}?from&to&adjust=none|forward|backward` | K 线（默认最近 90 天）；复权在读取层计算 |
| `GET /api/bars/{symbol}/rehab` | 复权因子 |
| `POST /api/bars/calendar/backfill` | 交易日历回补作业：券商段（约 2016-09 起）+ 更早的从日 K 线反推。幂等，几秒 |
| `GET /api/bars/calendar?from&to` | 交易日列表（默认最近一年）。`source=FUTU` 券商给的，`DERIVED` 从日 K 线反推 |
| `GET /api/bars/gaps?from&to&limit` | 对照交易日历深扫缺口（默认全历史）。**前收连续性检查查不出这类问题**：券商缺数时它自己的前收与缺口自洽 |
| `POST /api/bars/cleanup/phantom?apply=false` | 幽灵 K 线订正：落在交易日历之外的 K 线（券商在美股假日给过脏数据）。默认只试跑列清单，`apply=true` 才真删。**只删试跑列出来的那些**，一次最多 200 条：`found` 是总数、`listed`（= `bars` 长度）是本次列出也是本次最多会删的条数、`deleted` 只可能是 0 或 `listed`、`remaining` 是删完还剩多少；`found > listed` 时再跑一轮。3.1.2 前三处口径不一：审计按 20 封顶报条数、试跑按 200 封顶列清单、删除却按条件全删 |
| `GET /api/bars/audit?date=` | 日线数据审计（默认最近应有收盘 K 的交易日）：传入的日期若在日历里是休市日，只回一条 `calendar` 检查并判通过；completeness / sanity / continuity 为关键项，rehab / syncErrors / incrementJob / calendarCoverage / historyGaps / phantomBars / unsettledBars / gateway 为提示项；`ok` = 关键项全过。`unsettledBars`：收盘落定（美东 16:15）前写入的当天 K 线，可能是盘中价，下一次增量自动重拉覆盖 |
| `GET /api/bars/coverage` | 行数/标的数/最早最新、全量/池/持仓规模、已覆盖数、复权因子覆盖数、未解析数、错误数、历史额度、运行中的作业 |
| `GET /api/bars/quota` | 历史额度（7 天滚动） |
| `GET /api/jobs?limit=` / `GET /api/jobs/{id}` / `POST /api/jobs/cancel` | 作业记录与取消（在下一批边界停下） |

## 基本面（第 2 期·步骤 3）

估值快照全量每交易日一次，财报只做池与持仓、每周一次。两者都不占订阅额度与历史 K 线额度。

| 方法与路径 | 说明 |
| --- | --- |
| `GET /api/fundamentals/valuations?date=` | **全市场估值**（3.0.6）：某一天全部标的的估值快照，`{date, rows[]}`，`rows` 与单只估值同字段、按代码排序；`date` 缺省取最新有估值的一天，库里还没有估值时 `date=null`、`rows=[]`。名称、行业、指数、池角色请从 `GET /api/universe` 合并 |
| `GET /api/fundamentals/{symbol}` | 概览：最新估值 + 最近 4 期主要指标 + 公司简介 |
| `GET /api/fundamentals/{symbol}/valuation?from&to` | 估值时间序列（默认最近 90 天）。**亏损股的市盈率市净率为负是真实数据** |
| `GET /api/fundamentals/{symbol}/reports?statement&limit` | 财报期次与数据项；statement 取 `income`/`balance_sheet`/`cash_flow`/`main_index` |
| `GET /api/fundamentals/coverage` | 覆盖：最新估值日期、当天有估值的只数、财报期数、池里有财报的只数 |
| `GET /api/fundamentals/audit?date=` | 基本面审计；休市日直接判过 |
| `POST /api/fundamentals/valuation/refresh` | 估值快照作业（全量 ∪ 池 ∪ 持仓，一次 400 只）。只在收盘窗口（交易日美东 16:15 至次日 04:00）内可触发，窗口外 409：盘中取到的是实时价，会覆盖上一交易日按收盘算的估值 |
| `POST /api/fundamentals/financials/refresh?all=false` | 财报作业。`all=false` 只做池与持仓（约 80 秒，带公司简介）；`all=true` 做全量成分股（518 只约 41 分钟，不取简介） |

读这些数据前要知道的三件事：

- ETF 没有市盈率市净率，个股没有净值；两套口径共用一张表，缺的字段是 `null`。
- **富途把美股 REITs 也归为 Trust**，所以 `type=ETF` 的标的里绝大多数其实是房地产信托，它们有完整财报；净值只有真 ETF 才有。
- 年报与四季报的**期末可能是同一天**，靠 `periodText`（如 `2026/FY` 与 `2026/Q4`）区分。
- `fiscalYear` 可能领先自然年，排序与取"最近一期"一律用 `periodEnd`。


K 线字段：`tradeDate, open, high, low, close, lastClose, volume, turnover, turnoverRate(小数), changeRate(百分数), pe, blank`。

## 账户与持仓（第 3 期）

只读盈透。账户号只在服务端内存里，接口只给脱敏形式（`accountMask`），库里存的是带密钥的 HMAC（`accountKey`）。
快照每个交易日美东 18:00 自动拍，21:00 补偿检查兜底；同一账户同一天重拍覆盖。

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/account/snapshot?force=false` | 账户快照作业：持仓 + 资金汇总 + 按收盘价估值 + 对账。**只能在快照窗口内拍**（交易日美东 16:15 至次日 04:00），窗口外 409——盈透只给当前持仓，过去的日子补不回来。`force=true` 只在开发环境可用，按最近一个已收盘交易日口径拍，用于验证 |
| `GET /api/account/snapshots/latest` | 最新一份快照：`snapshot`（资金、本系统估值 `positionValue`、`reconStatus`、`recon` 各项明细）+ `positions` + `change`（与上一份快照相比：`netLiquidationChange` 含出入金；`positionPnl` = Σ 两份快照**数量相同**的持仓 数量 × 价差——数量变过的一律不计，条数记在 `excludedPositions`（拆股/合股与买卖在快照里长得一样，而拆股当天数量与价格同时按比例变，按旧口径 3:1 拆股会给出 `10 × (100 − 300) = −2000` 这种量级错数，3.1.2 修）；`positionsChanged=true` 表示持仓集合或数量变过（买卖，或拆股/合股），只是近似；没有上一份时为 null）；还没有 → 404 |
| `GET /api/account/snapshots?from&to` | 快照序列（默认最近 90 天），不含持仓明细 |
| `POST /api/account/holdings/sync?apply=false` | 按盈透持仓维护池里的 HOLDING。默认只返回计划（`plan.changes` 的 `ADD` / `PROMOTE` / `RETURN_TO_POOL` / `REMOVE`），`apply=true` 才改池，并触发实时订阅对账与深度回补；盈透返回空持仓而池里还有 HOLDING 时不执行（`plan.blocked`）；盈透未连接 → 503 |
| `GET /api/account/audit?date=` | 账户审计（收盘巡检第三段），默认审最近一个已收盘交易日：当天快照是否存在（美东 18:30 前、或从来没有过快照即刚启用时，缺快照只提示）、对账状态（FAIL 为关键项，WARN 只提示）、缺价、最近一次快照作业；休市日直接判过 |
| `GET /api/account/live` | **实时账户**（3.0.2）：盈透常驻订阅，只读内存、不落库。**按需**：第一次读发起订阅（`status=WARMING`，通常 2 秒内到齐），5 分钟没人读自动退订。字段见下 |

实时账户 `GET /api/account/live` 的字段。**全部是盈透原值，不做任何折算**（3.0.3 起；与盈透 App 一致），各部分自带更新时间，本来就不同步：
- `status`：`LIVE` 数据在推送；`WARMING` 刚订阅；`DISCONNECTED` 网关断了，保留断线前最后收到的数据；`UNAVAILABLE` 取不到（网关未启用 / 未连接 / 选不出账户），`detail` 说明原因。
- `accountMask`、`currency`、`startedAt`（本次订阅开始时间）、`lastError`（订阅被券商拒绝时的说明，例如账户汇总超出每客户端 2 个的上限 322）。
- `money`：盈透账户汇总——`netLiquidation`（净值）、`totalCash`、`availableFunds`、`buyingPower`、`excessLiquidity`、`grossPositionValue`、`stockMarketValue`、`accruedDividend`、`updatedAt`。**约 3 分钟才推一次**（实测），可能比盈透 App 晚几分钟。
- `pnl`：盈透账户盈亏 `daily`（当日）、`unrealized`（浮动）、`realized`（当日已实现）、`updatedAt`；按变化秒级推送，盘前盘后盈透用扩展时段价重算。**还没收到有效推送时整个为 null**（首条推送不对、被丢弃；10 秒没有有效值网关会重订，最多 3 次）。
- `positions[]`：`symbol`（类别股已换成点，如 `BRK.B`）、`conId`、`securityType`、`quantity`、`averageCost`；`last` / `lastAt` / `lastDelayed` 为盈透行情最新价（与 App「最新价」同源，行情未到时为 null，`lastDelayed=true` 表示降级成延迟行情）；`priorClose` 为行情前收（与 App「PRIOR CLOSE」同值）；
  与 App 同口径的派生项（3.0.4）：`change` = last − priorClose、`changePct`、`costBasis` = averageCost × quantity（App 的 Cost Basis）、`portfolioPct` = marketValue ÷ money.netLiquidation × 100（App 的 % of Portfolio），缺输入时为 null，均两位小数；
  `marketValue`、`dailyPnl`、`unrealizedPnl`、`updatedAt` 来自盈透逐只盈亏。盈透的市值按它自己的估值价算，**不一定等于 last × quantity**（实测 GOOG 估值 344.41、最新价 346.08）。
  `cashEquivalent`；`positionsUpdatedAt` 为持仓列表最近一次变化。金额保留两位小数（格式化，不是计算）。

持仓同步规则：持有而池里没有的加为 HOLDING（库里也没有的先向富途解析建档）；池里是 POOL 的升为 HOLDING、清仓后回 POOL；
池里是 BENCHMARK 的不动（基准不因买卖改变）；其余 HOLDING 清仓后移出池（K 线与基本面保留）；现金管理工具与非美股持仓不算。
快照作业每次都会先同步再对账；生产实例在盈透连上 60 秒后也同步一次（开发实例不自动同步）。

持仓的 `priceSource`：`BAR` 当日 K 线收盘；`SNAPSHOT` 富途快照价（库里没有当日 K 线的持仓兜底；收盘后快照价冻结在当日收盘，只在下一个交易日 04:00 前采用）；`NONE` 缺价。
`cashEquivalent=true` 是配置里的现金管理工具：计入市值，不进池、不参与持仓集合核对。

对账四项，`reconStatus` 取最差：

- `identity`：现金 + 股票市值 + 应计股息 = 净值，差 ≤ 1 美元 OK、≤ 净值 0.1% WARN、否则 FAIL（只适用于纯股票账户）；
- `marketValue`：Σ 数量 × 收盘价 对比盈透股票市值，≤ 0.2% OK、≤ 1% WARN、否则 FAIL；有持仓缺价时 WARN；
- `holdings`：持有的股票与池里的 HOLDING 角色一致（基准与现金管理工具不参与），不一致或库里没有该标的时 WARN；快照作业先同步再对账，正常应为 OK；
- `otherAssets`：非股票持仓不参与估值，有就 WARN。

## 入场信号（第 4 期）

判据版本 `sentinel-v1`（四门：趋势 / 定位 / 触发 / 风控，设计见 ARCHITECTURE §18）。每个交易日美东 18:10 评估全量成分股 ∪ 池与持仓（去掉基准），22:00 补偿检查；不调网关与模型。
价量口径是**结构口径**：以判定日为基准前复权，价格调拆股、合股、送股、分拆、特别股息，不调普通分红；成交量只按股数比例调。

| 方法与路径 | 说明 |
| --- | --- |
| `GET /api/signals/evaluate/{symbol}?date=` | 单日判定。`date` 缺省取最近收盘落定的交易日，晚于它 409，非交易日 400。返回 `evaluation`（`status`、`gates[]` 每道门的 `verdict` PASS/FAIL/UNAVAILABLE、`criteria` 判据原文、`values` 代入值；`indicators`、`zones` 支撑区、`hitZone`、`exitPlan` 出场预案、`bonus` 加分项）、`droppedNonTradingDays`（交易日历之外被剔除的 K 线）、`missingTradingDays`、`thresholds` 参数全集 |
| `GET /api/signals/replay/{symbol}?from&to&trades=false&stopAtr&half=false` | 区间回放，默认最近一年、最长 21 年。`days[]` 每天的状态、通过门数、首个未过的门、`outcome`（按边沿与冷却：SIGNAL / NO_SIGNAL / SUPPRESSED_EDGE / SUPPRESSED_COOLDOWN；历史上没有 AI 结论，不含否决层）、收盘、ATR、RVOL、命中区底、止损与止损距离，`gates` 为四门缩写（P 通过 / F 不过 / U 不可判定）；另给 `statusCounts` / `outcomeCounts`。`trades=true` 附每条信号的纸面交易 `trades[]`（次日开盘入场，`reason` STOP / CHANDELIER / TIME / OPEN，`r` 为 R 倍数、未平仓为 null，`mfeR` / `maeR`；价格为收盘落定日口径）；`stopAtr`（默认 2.0）与 `half`（+1R 减半仓）只改纸面交易的出场、不改判定，供同一批信号配对比较，`exitVariant` 写明所用变体 |
| `POST /api/signals/evaluate?date=` | 提交评估作业（写库），返回 `{jobId}`。`date` 缺省取收盘落定日；过去的日期为补跑，产生的信号 `origin=BACKFILL`；晚于收盘落定日 409、非交易日 400、有作业在跑 409。同一天重跑：评估覆盖，已发出的信号不动 |
| `GET /api/signals?from&to&status&scope=pool&origin` | 信号列表，默认最近 30 天、只看池与持仓（`scope=all` 含池外），`status` 逗号分隔。每条是 `{signal, base}`：`signal` 的价位为判定日口径（`close`、`stop`、`stopLeg` ATR/ZONE、`riskPerShare`、`plusOneR`、`chandelierStop`、`target`、`rewardRisk`、命中区、`bonus`），`status` NEW / ACKNOWLEDGED / DISMISSED / EXPIRED / VETOED，`expiresOn` 为判定日后第 2 个交易日（该日评估后 NEW 与 ACKNOWLEDGED 过期）；`base` 是 BASE 变体的账本行 |
| `GET /api/signals/{id}` | 信号详情：`signal`、`evaluation`（当天评估行，`detail` 为判定明细）、`fingerprintMatches`（按当前库里数据重算的输入指纹与存档一致与否）、`recomputed`（存档没有明细时现场重算）、`tracks`（两个出场变体）；不存在 404 |
| `POST /api/signals/{id}/status` | body `{"status": "ACKNOWLEDGED" \| "DISMISSED", "note": "..."}`。NEW → ACKNOWLEDGED / DISMISSED、ACKNOWLEDGED → DISMISSED，其余 409 |
| `GET /api/signals/evaluations?date&outcome&gate&scope=all` | 某天的全部评估（回答"为什么没信号"）：状态、`outcome`、`gates` 缩写、通过门数、首个未过的门、`role`（POOL / HOLDING / UNIVERSE）、收盘、ATR、RVOL、区底、止损、`inputFingerprint`；`detail` 只给池与持仓、至少过三门、结果不是 NO_SIGNAL 的存，其余为 null（用单日判定接口现场看）。`outcome` 也接受 SKIPPED_* 状态名，`gate` 按首个未过的门过滤 |
| `GET /api/signals/evaluations/{symbol}?from&to` | 单只评估历史，默认最近 90 天，倒序 |
| `GET /api/signals/ledger?variant&status` | 纸面账本。`stats[]` 按变体（`BASE` 止损 2.0×ATR、`STOP_2_5` 止损 2.5×ATR，都不减半仓）、来源、模型裁决（`ai`：VETO 被否决 / ALLOW 调过模型并放行 / NONE 没有模型结论）分开：总数、未平、待入场、已平、胜率、每笔 R、每笔收益率；`pairedCount` / `pairedMeanReturnDiff` 为同一批已平仓信号上 STOP_2_5 − BASE 的收益率差；`entries[]` 为 `{signal, track}`。账本行 `status` PENDING_ENTRY / OPEN / CLOSED，`exitReason` STOP / CHANDELIER / TIME，价格为判定日口径，未平仓每天从信号日整段重算，`updatedThrough` 为算到的日期 |
| `GET /api/signals/audit?date=` | 信号审计（收盘巡检第五段）：`evaluationExists` / `coverage` / `ledgerCurrent` 为关键项（美东 19:00 前缺评估只提示）；`staleData`（超过目标 2% 才判不过）、`dataQuality`、`signalConsistency`（当天信号与重算后的评估不一致，信号保留）、`aiAnalyses`（当天模型分析里没有结论的次数与原因、证据核对不通过的条数）、`evaluationJob` 为提示项；休市日直接判过 |
| `GET /api/signals/ai-input/{symbol}?date=` | 预览发给模型的输入（不调模型、不计费）：`meta`（行业、所属指数）、`signal`（四门判据原文、支撑区、止损、出场预案）、`technicals`（均线距离、ATR%、RVOL、20/60/250 日涨跌与相对 SPY 超额、52 周位置、最近 15 根日 K）、`valuation`（估值快照、静态市盈率 5 年分位）、`financials`（最近 8 个单季与 3 个年度的白名单科目，金额为百万美元 `*UsdM`）、`calendarHint`（距最近季报期末天数与下一次财报的粗估窗口）、`profile`、`caveats`。不含任何持仓与账户信息。当天不予判定 409 |
| `GET /api/signals/bars/{symbol}?asOf&from&to` | 画图用的日 K：整段按结构口径换算（跨拆股连续），再折回 `asOf` 那天的价格尺度，与当天的信号价位（止损、+1R、支撑区）对齐。默认 `to` = 收盘落定日、`asOf` = `to`、`from` = `asOf` 往前一年，区间最长 3 年；`asOf` 当天没有 K 线时取之前最近一根定尺度。返回 `[{tradeDate, open, high, low, close, volume}]` |

`status` 取值：`EVALUATED`；`SKIPPED_INSUFFICIENT_BARS`（窗口 600 自然日内少于 260 根）；`SKIPPED_STALE_DATA`（判定日没有 K 线）；
`SKIPPED_DATA_GAP`（对照交易日历缺超过 3 个交易日，停牌空 K 也算缺）；`SKIPPED_CORPORATE_ACTION`（股数变动事件缺比例，等复权因子重拉）。

## 模型第二意见（第 4 期·步骤 4）

模型只有否决权（设计与实测见 ARCHITECTURE §18.8）。18:10 评估作业里，对过了边沿与冷却的候选调用（只在 `trader.ai.signal-veto-enabled=true` 的实例、且是实盘判定；补跑不调）。
否决 = 立场 BEARISH / AVOID 且把握不是 LOW 且至少 2 条核对通过的看空证据；否决的信号状态为 `VETOED`、评估结果为 `BLOCKED_BY_AI`，照样进纸面账本。
失败、拒答、截断、结构非法、预算跳过都按没有结论（`ABSENT`）放行。发给模型的输入不含持仓与账户信息（预览见 `GET /api/signals/ai-input/{symbol}`）。

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/ai/analyses?symbol&date=` | 手工分析（**同步约 15 秒，会计费**；计入每日上限；同一标的、判定日、提示词、模型、输入哈希已有 OK 的直接复用）。当天不予判定 409。返回落库的一行 |
| `GET /api/ai/analyses?from&to&status&symbol&limit=100` | 按创建日（美东）过滤，默认最近 7 天，倒序 |
| `GET /api/ai/analyses/{id}` | 一次分析：`purpose`（SIGNAL_VETO / MANUAL）、`signalId`、`promptVersion`、`model`、`reasoningEffort`、`inputHash`、`input`（发给模型的 JSON）、`status`（OK / REFUSED / TRUNCATED / INVALID / FAILED / SKIPPED_BUDGET / FAILED_DATA）、`judgment`（`stance`、`confidence`、`summary`、`bullEvidence[]` / `bearEvidence[]` 每条 `{field, value, point}`、`risks`、`dataGaps`、`vetoReason`）、`outputText`、`verdict`（VETO / ALLOW / ABSENT）与 `verdictReason`、`checks`（逐条证据核对）、`verifiedBear` / `unverified`、`error`、token 用量（输入含缓存命中、输出含推理）与耗时；不存在 404 |
| `GET /api/ai/usage?from&to` | `settings`（是否配置密钥、模型、开关、每日上限、作业时长预算、推理强度、提示词版本；不含密钥）+ `days[]` 按美东自然日汇总：行数、实际调用次数、OK、失败、预算跳过、否决、各类 token |

## 分部估值 SOTP（第 5 期）

把一家公司按业务线拆开估值：每条业务线 量 × 价 = 营收 → × 净利率 = 净利 → × 本益比 = 业务价值；
目标年股价 =（Σ 业务价值 + 目标年净现金）÷ 目标年股数，再按要求回报率**折回基准日**才能和现价比。
设计与实测见 ARCHITECTURE §21。

**这组接口不预测、不给建议，也不联动入场信号与纸面账本。** 券商只给合并报表，没有分部量价、没有一致预期，
所以各业务线的假设必须使用者自己填；系统负责自动带入公司级底座、做算术、做一致性核对（净利率口径、单因子敏感度、反推）。

口径约定：`netMargin` 与 `discountRate` 都收**小数**（7% 传 0.07），写成百分数直接 400；
三个情景 `BEAR` / `BASE` / `BULL` 缺一不可（期权型业务单点估值没有意义）；每条业务线的 `scopeNote` 必填，用来挡重复计算。

| 方法与路径 | 说明 |
| --- | --- |
| `GET /api/valuation/sotp/{symbol}/inputs` | 自动带入的底座：现价与日期；`shares` **三种口径都给、不替使用者选**（券商流通股、市值 ÷ 现价、归母净利 ÷ 稀释每股收益；2026-09-19 实测 TSLA 39.5 亿 vs 35 亿对不上，差额未核实）；`netCash`（现金及短投 − 短期借款 − 长期借款，**融资租赁单列、默认不计入**，带取自哪一期）；TTM 营收与归母净利（只认期别带 `/Q` 的四个季报——年报与四季报期末是同一天）；`netMarginTtm`（小数）；`peMedian`（近 5 年日 K 市盈率**正值**中位数，0 是无数据不是真值）；`applicability`（APPLICABLE / CAUTION / NOT_APPLICABLE + 逐条原因：取不到财报、亏损、金融业该用 PB+ROE、REITs 该用 FFO）|
| `GET /api/valuation/sotp/{symbol}` | 该标的已存的方案，按更新时间倒序，每套都带底座与算好的结果。`asOf` 是存下来的估值基准日（原样回显），`valuedAt` 是**本次实际折算到的日期** = 现价所属交易日与 `asOf` 中较晚的那个；3.1.2 前一律按冻结的 `asOf` 折现却拿今天的现价比涨跌幅，两个日期对不上，方案存得越久偏差越大 |
| `POST /api/valuation/sotp/calc/{symbol}` | 试算，**不落库**。请求体同下 |
| `POST /api/valuation/sotp/{symbol}` | 保存 / 更新（按 `name` 覆盖，同一标的下唯一）。请求体：`{name, asOf, targetYear, discountRate, targetShares, targetNetCash, note, segments:[{name, scopeNote, cases:{BEAR:{volume,price,netMargin,pe}, BASE:{…}, BULL:{…}}}]}` |
| `DELETE /api/valuation/sotp/{id}` | 删一套；不存在 404 |

返回的 `result`：`horizonYears` / `discountFactor`；`scenarios` 三个情景各给逐业务线价值、业务价值合计、股权价值、
目标年股价、**折回基准日的股价**与相对现价的涨跌幅；`sensitivities` 单因子敏感度（只动一条、其余保持基准，按摆幅倒序）；
`marginCheck`（基准情景的隐含合并净利率 vs 最近一期实际，偏离超过 10 个百分点 `ok=false`）；
`reverse` 反推（现价按要求回报率隐含的目标年股价、市值、按本益比基准倒推的所需净利与所需年化增长）——**缺本益比基准时整个为 null，不硬编倍数**。

## 实时报价（第 2 期·步骤 2，不落库）

报价里有三个价格字段，别混用：`price` 是按时段取的有效价，`rthPrice` 是常规时段价（盘前盘后冻结在上个收盘），
`lastClose` 是券商给的"昨收"。**盘前盘后 `lastClose` 不随时段推进**，仍是上一个常规时段的前收，
拿它算涨跌会与 `changeRate` 对不上；要展示基准价用 `referenceClose`（常规时段等于 `lastClose`，盘前盘后等于 `rthPrice`）。

| 接口 | 说明 |
| --- | --- |
| `GET /api/quotes` | 缓存里的全部最新报价 |
| `GET /api/quotes/{symbol}` | 单个；未订阅或尚未收到推送 → 404 |
| `GET /api/quotes/stream` | SSE：`event: quotes`（数组，只含上一帧后变过的）每秒最多一帧；`event: status` 每 15 秒；连接 30 分钟超时，客户端自动重连 |
| `GET /api/quotes/status` | enabled / paused / desired / subscribed / deferredUnsubscribe / quota（usedQuota、remainQuota、byType）/ cached / totalPushes / pushesLastMinute / lastPushAt / streamClients / lastError |
| `GET /api/quotes/subscriptions` | 轻量的订阅集合状态：desired / subscribed / paused（不含额度与推送统计，那些看 `/status`） |
| `POST /api/quotes/subscriptions/reconcile` | 对账：期望 = 池 ∪ 持仓；返回 desired / subscribed / added / removed / deferred / error |
| `POST /api/quotes/subscriptions/pause` / `resume` | 暂停（反订阅全部、清缓存）/ 恢复 |

报价字段：`instrument, session(PRE|RTH|AFTER|OVERNIGHT|CLOSED), price, change, changeRate(百分数), open, high, low, rthPrice, lastClose, volume, turnover, preMarket{price,change,changeRate,volume}, afterMarket{…}, overnight{…}, quoteTime, receivedAt, suspended`。`price/change/changeRate` 是按时段取的有效价。

## Actuator

- `GET /actuator/health` — `{"status":"UP"}`，含各组件明细；组件 `gateways` 在有网关启用但未连接时为 `DEGRADED`，总状态随之为 `DEGRADED`，HTTP 仍是 200。
- `GET /actuator/info` — build-info（版本、构建时间）。
- `POST /actuator/shutdown` — 仅本机开发与生产外置配置开启，供 `run-local.sh stop` / `bin/trader.sh stop` 使用。
