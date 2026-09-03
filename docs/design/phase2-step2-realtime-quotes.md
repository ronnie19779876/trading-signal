# 第 2 期 · 步骤 2 设计：实时行情订阅（不落库）

> 状态：**已认可并实现**（2026-09-03）。要点并入 docs/ARCHITECTURE.md §12，本文保留为设计记录。
> 不含主机名、IP、账户号、网关端口。富途接口行为均对真实 OpenD 实测（美东盘前 05:58 ET）。

## 1. 实测事实（决定方案的部分）

| 事实 | 对设计的影响 |
| --- | --- |
| 订阅额度按「标的 × 订阅类型」计（2 只 × Basic/Ticker/OrderBook = 6），本账号总额度 100，`getSubInfo(isReqAllConn=true)` 能看到全部连接的已用/剩余 | 池 50 + 持仓 8 只订 **Basic 一种** = 58 个额度；再加逐笔或盘口就超 100 |
| 盘前 75 秒 Basic 推送 29 条（26 条内容不同）：`curPrice / volume / updateTime` **冻结在昨日 16:00 收盘**，但 `preMarket{price, changeVal, changeRate, volume}` 实时更新；盘后同理有 `afterMarket` | 报价的"有效价"要按时段取：常规时段用 curPrice，盘前/盘后用 preMarket / afterMarket 子结构 |
| 逐笔（Ticker）在盘前只收到首推的收盘那笔，没有盘前成交 | 本步骤不订逐笔；步骤 5 下单时按需临时订 |
| 盘口（OrderBook）盘前推送活跃（75 秒 160 条） | 本步骤不订盘口（额度与流量），下单时按需订 |
| 订阅满 1 分钟后反订阅成功，额度立即释放 | 反订阅前记录订阅时刻 |
| `getBasicQot` 需先订阅；`isFirstPush=true` 订阅成功后立即推一条当前值 | 订阅即得首个快照，不必再拉 |
| 第 1 期心跳每 30 秒取的 `getGlobalState.marketUS` 给出市场状态（PreMarketBegin / Morning / Afternoon / AfterHoursBegin / AfterHoursEnd …） | 时段判定用它，拿不到时退回美东时钟 |

## 2. 范围

| 做 | 不做（后续） |
| --- | --- |
| 对标的池 + 持仓订阅 Basic 报价并注册推送；池变动、断线重连后自动对账订阅 | 逐笔、盘口、分钟 K 推送（步骤 5 下单时按需） |
| 内存报价缓存（不落库）：最新报价、时段、有效价、盘前盘后、推送统计 | 报价落库、历史 tick |
| 对外：REST 快照、SSE 推流（1 秒合并一次）、订阅状态与额度 | WebSocket、告警 |
| 与全量轮转的额度协调：轮转期间暂停实时订阅，结束后恢复 | 盈透行情 |
| 前端「行情」页新增实时报价表（SSE 自动刷新）与 **K 线图表**（lightweight-charts，日 K + 成交量，复权切换） | 分时图 |

## 3. 领域与端口

```java
// trader-domain
public enum MarketSession { PRE, RTH, AFTER, OVERNIGHT, CLOSED }
public record Quote(Instrument instrument, MarketSession session,
                    BigDecimal price, BigDecimal change, BigDecimal changeRate,      // 有效价：RTH=curPrice；PRE/AFTER=对应子结构；CLOSED=curPrice
                    BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal rthPrice, BigDecimal lastClose,
                    long volume, BigDecimal turnover,
                    SessionQuote preMarket, SessionQuote afterMarket,                  // 可空：price/change/changeRate/volume
                    Instant quoteTime, Instant receivedAt, boolean suspended)
public record SubscriptionInfo(int usedQuota, int remainQuota, int totalQuota, Map<String,Integer> byType, Instant checkedAt)

// trader-gateway-api：MarketDataGateway 增加
CompletableFuture<Void> subscribeQuotes(List<Instrument> instruments);
CompletableFuture<Void> unsubscribeQuotes(List<Instrument> instruments);
CompletableFuture<SubscriptionInfo> subscriptionInfo();
void addQuoteListener(QuoteListener listener);        // onQuote(Quote)，在网关的 dispatch 线程上，不得阻塞
```

富途实现：`FutuChannel.qotSpi.onPush_UpdateBasicQuote` → `FutuQuotes.toQuote(BasicQot, marketState)` → 转到 `futu-dispatch` 线程通知监听器（SDK 回调线程只做分发，沿用第 1 期纪律）。订阅请求 `sub(Basic, isRegOrUnRegPush=true, isFirstPush=true)`，一次最多 100 只。`getSubInfo` 限频保守设 10/30s。

时段判定 `MarketSession`：优先 `marketUS`（PreMarketBegin→PRE；Morning/Afternoon/Rest→RTH；AfterHoursBegin→AFTER；NightOpen→OVERNIGHT；其它→CLOSED），拿不到时按美东时钟（04:00–09:30 PRE，09:30–16:00 RTH，16:00–20:00 AFTER，其余 CLOSED）。

## 4. 核心（trader-core.quotes）

| 组件 | 职责 |
| --- | --- |
| `QuoteCache` | `symbol → Quote`；推送计数、最近推送时刻、每分钟推送数（滑动）；线程安全 |
| `QuoteSubscriptionService` | 期望集合 = 池 ∪ 持仓（富途已解析、未退市）；`reconcile()`：对比当前已订集合，新增订阅、多余的反订阅（未满 1 分钟的延后）；触发点：应用启动（`auto-subscribe`）、池增删、网关 `onConnected(reconnected=true)`、手工；`pause()/resume()` 供轮转使用；记录每只的订阅时刻 |
| `QuoteStreamService` | SSE 客户端集合；每 `stream-interval`（1 秒）把变化过的报价合并成一帧广播；15 秒无变化发心跳注释；客户端断开自动清理 |
| `RotationRefresher` 改动 | 开始前若 `pause-during-refresh` 则 `pause()`（释放 58 个额度，批次仍可 90 只）；结束后 `resume()`。否则批次大小 = min(配置, 剩余额度 − 预留) |
| `PoolService` 改动 | 增删后调用 `reconcile()` |

不落库：进程重启后缓存为空，订阅成功后首推即填充。

## 5. REST 与前端

| 接口 | 说明 |
| --- | --- |
| `GET /api/quotes` | 全部缓存报价（数组） |
| `GET /api/quotes/{symbol}` | 单个；未订阅/无数据 → 404 |
| `GET /api/quotes/stream` | SSE：`event: quotes` 每帧一个报价数组（只含变化的）；`event: status` 每 15 秒一次订阅状态 |
| `GET /api/quotes/status` | 订阅数、期望数、额度已用/剩余、最近推送时刻、每分钟推送数、暂停中否、时段 |
| `POST /api/quotes/subscriptions/reconcile` / `pause` / `resume` | 手工控制 |

前端：行情页顶部加「实时报价」卡片（SSE 连接状态、时段、订阅/额度、表格：代码、名称、角色、有效价、涨跌%、盘前/盘后价、成交量、更新时刻；页面不可见时断开 SSE），点行选中 → 下方「K 线」卡片用 lightweight-charts 画日 K（蜡烛 + 成交量柱），复权口径与区间可切换（数据来自已有的 `GET /api/bars/{symbol}`）。

## 6. 配置

```yaml
trader:
  marketdata:
    realtime:
      enabled: true              # 关掉则不装配订阅与 SSE
      auto-subscribe: false      # 开发机 false / 发布包 true：启动即订阅池与持仓
      reserve-quota: 10          # 给临时用途留的订阅额度
      pause-during-refresh: true # 全量轮转期间暂停实时订阅
      stream-interval: 1s
      unsubscribe-min-age: 61s   # 券商规则：订阅满 1 分钟才能反订阅
```

## 7. 测试与验收

- 单元：`FutuQuotes` 映射（用实测的盘前 BasicQot 样本：curPrice 冻结、preMarket 更新 → 有效价取 preMarket）、时段判定、对账差集（含"未满 1 分钟延后反订阅"）、缓存与每分钟统计、SSE 合并帧。
- 集成（真实 OpenD）：订阅 AAPL/TSLA Basic → 60 秒内收到 ≥1 条推送 → `subscriptionInfo` 已用 +2 → 反订阅 → 额度释放。
- 验收：本机启用 `auto-subscribe` 后系统页/行情页显示池与持仓的实时报价在跳（盘前看 preMarket 价变化）；`GET /api/quotes/status` 额度 = 58 已用；池增删后订阅数跟着变；执行全量轮转时订阅暂停、结束后恢复；重启 OpenD 连接（用第 1 期的中继测试方式）后自动重订阅。

## 8. 待拍板

1. 只订 **Basic**（池 + 持仓 58 个额度），逐笔与盘口留到步骤 5 按需订（建议）。
2. 有效价按时段取：RTH 用 curPrice，盘前/盘后用 preMarket/afterMarket（建议）。
3. 全量轮转期间**暂停**实时订阅、结束恢复（建议；替代方案是把轮转批次缩到 ~30 只、增量约 18 分钟）。
4. 推流用 **SSE**（建议，Spring MVC 原生、同源、无需额外依赖）；WebSocket 留到需要双向时。
5. 开发机 `auto-subscribe: false`、发布包 true（避免开发与生产两个实例同时占 116 个额度）。
6. K 线图表本步骤用 lightweight-charts 一并做（建议）。
7. 报价不落库（设计原则，本步骤不留任何 tick 表）。
