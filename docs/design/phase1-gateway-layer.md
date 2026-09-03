# 第 1 期设计：网关接入层

> 状态：**已认可并实现**（2026-09-03）。实现要点已并入 docs/ARCHITECTURE.md §10，本文保留为设计记录。
> 不含主机名、IP、账户号、网关端口。SDK 签名均已用 javap 对 tws-api 10.30.01 与 futu-api-shaded 10.10.7008 核实。

## 1. 目标与范围

第 1 期只做"连得上、连得稳、看得见"，为第 2~5 期的行情、账户、下单铺路：

| 做 | 不做（后续期数） |
| --- | --- |
| 两家网关的连接生命周期：连接、就绪判定、断线检测、指数退避重连、优雅关闭 | 行情订阅、K 线、基本面（第 2 期） |
| 请求-回调关联：盈透 reqId → Future，富途 seq → Future；超时、失败、取消 | 账户资金、持仓、盈亏快照（第 3 期） |
| 健康探测：盈透心跳 `reqCurrentTime`，富途 `getGlobalState` | AI 分析、信号（第 4 期） |
| 限频闸门：通用限流器 + 两家各自的策略 | 下单、改撤单、富途 `unlockTrade`（第 5 期） |
| 第一批只读请求：盈透 `managedAccounts` / `reqCurrentTime` / `reqContractDetails`；富途 `getGlobalState` / `getAccList` | |
| 可观测：`GatewayStatus` 扩展、`GET /api/gateways*`、系统页实时状态、`gateway_event` 审计表、actuator 健康指标 | |
| 单元测试 + 对真实网关的集成测试（默认跳过） | |

验收标准见 §10。

## 2. 端口层（trader-gateway-api）变化

```java
public interface BrokerGateway {
    Broker broker();
    GatewayStatus status();
    CompletableFuture<Void> connect();                 // 幂等；完成 = 首次就绪；失败进入重连不抛异常
    void disconnect();                                 // 幂等、同步；停止重连
    CompletableFuture<List<AccountRef>> accounts();    // 盈透：受管账户；富途：交易业务账户
    void addListener(GatewayListener listener);
}

public interface GatewayListener {                     // 回调在网关的调度线程上，不得阻塞
    default void onConnected(Broker broker, boolean reconnected) {}
    default void onDisconnected(Broker broker, String reason) {}
    default void onError(Broker broker, GatewayException error) {}
}

public interface ReferenceDataGateway {                // 本期只有盈透实现
    CompletableFuture<List<InstrumentInfo>> lookup(Instrument instrument);
}

public enum GatewayState { DISABLED, DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, ERROR }

public record GatewayStatus(GatewayState state, String detail, Instant checkedAt,
                            Instant connectedSince, Instant lastHeartbeatAt, int reconnectAttempts,
                            Map<String, String> facts) {}   // facts：可公开的事实，如 serverVersion、accounts=1、farms、opendVersion、qotLogined
```

- `GatewayException`（`broker`、`code`、`message`、`retryable`）及子类 `NotConnectedException`、`RequestTimeoutException`、`RequestRejectedException`。
- 领域模型新增（trader-domain）：`AccountRef(Broker broker, String accountId, AccountKind kind /*LIVE|PAPER*/, Set<Market> markets)`；`InstrumentInfo(Instrument instrument, String brokerRef /*conId*/, String name, String primaryExchange, String currency, BigDecimal minTick, ZoneId timeZone, String tradingHours, String liquidHours)`。账户号只在服务端内存里，对外一律经 `Masking.mask` 脱敏。

## 3. 连接状态机与重连（两家共用）

`trader-gateway-api` 提供与 SDK 无关的 `ConnectionSupervisor`（`org.jdkxx.trader.gateway.support`），适配器只实现 `Transport`：

```java
public interface Transport {
    CompletableFuture<Void> open();      // 建连并等待"就绪"（盈透：nextValidId；富途：onInitConnect errCode==0）
    void close();                        // 幂等
    CompletableFuture<Boolean> probe();  // 心跳（盈透 reqCurrentTime；富途 getGlobalState）
}
```

```
DISCONNECTED ──connect()──▶ CONNECTING ──就绪──▶ CONNECTED
     ▲                          │失败                │ 传输层报断线 / 心跳连续失败
     │                          ▼                    ▼
     └───disconnect()──── RECONNECTING ◀─────────────┘   退避：5s ×2 ≤60s（±20% 抖动），次数默认不限
```

- 退避与心跳由 `ScheduledExecutorService` 驱动，时钟与调度器可注入，状态机用假调度器做单元测试。
- 心跳：每 30 秒 `probe()`，10 秒无应答记一次失败，连续 2 次 → 主动 `close()` 并进入 RECONNECTING（对付"连接还在但对端已死"）。
- 重连成功触发 `onConnected(reconnected=true)`，第 2 期的订阅恢复就挂在这个事件上。
- `disconnect()` 取消一切调度，状态 DISCONNECTED；`ERROR` 只用于**不可重试**的情况（配置非法、账户不在受管列表）。
- 每次状态变化写日志并通知监听器；`reconnectAttempts` 计数在成功后清零。

## 4. 盈透适配器（trader-gateway-ibkr）

| 类 | 职责 |
| --- | --- |
| `IbkrConnection implements Transport` | 持有 `EClientSocket` / `EJavaSignal` / `EReader` / 泵线程 `ibkr-pump`。`open()`：在工作线程 `eConnect(host, port, clientId)`（同步握手，`connect-timeout` 兜底）→ 启动 `EReader` → 循环 `signal.waitForSignal(); reader.processMsgs()` → 等 `nextValidId` 才算就绪；同时收下 `managedAccounts`、`serverVersion()`、`getTwsConnectionTime()`。`close()`：`eDisconnect()` + 结束泵线程 |
| `IbkrWrapper extends DefaultEWrapper` | 唯一的回调入口。连接级消息（`connectAck` / `nextValidId` / `managedAccounts` / `connectionClosed` / `error(id=-1,…)`）交给连接；带 reqId 的回调交给 `IbkrRequestRegistry`；农场消息 2104/2106/2158/2103/2105/2107/2108 与 1100/1101/1102 更新 `facts` |
| `IbkrRequestRegistry` | `reqId → PendingRequest<T>`（`onItem` 累积、`complete` 收尾、`fail`）。reqId 从 10_000_000 起自增，与 orderId 空间分开（`error(id,…)` 里 id 两种含义共用）。`request-timeout` 默认 15 秒，超时可触发登记的取消动作。Future 的完成在单线程 `ibkr-dispatch` 上执行，**绝不在泵线程上跑用户回调** |
| `IbkrRateLimiter` | 令牌桶，默认 40 条/秒（IB 上限 50），每次向 `EClientSocket` 发请求前 `acquire()` |
| `IbkrClient` | 包内门面：`currentTime()`、`contractDetails(Contract)`、`managedAccounts()` |
| `IbkrGateway implements BrokerGateway, ReferenceDataGateway` | 对外唯一入口；`status()` 由 supervisor 状态 + facts 组装；`accounts()` 把受管账户映射成 `AccountRef`（`DU` 开头为 PAPER）；`lookup()` → `reqContractDetails` → `InstrumentInfo`（`minTick`、`timeZoneId`、`liquidHours`/`tradingHours` 原样保留，第 2 期解析交易时段） |

错误码处理（本期）：

| 码 | 处理 |
| --- | --- |
| 502 连不上 / 507 对端关闭 / `connectionClosed()` | 进入 RECONNECTING |
| 326 client-id 已被占用 | 同上，但 `detail` 明确写"client-id 被占用，检查是否有别的实例用了同一个 id" |
| 1100 | 保持 CONNECTED，`facts.connectivity=LOST`，detail 提示；不触发重连（TWS 会自愈） |
| 1101 | 数据丢失 → 触发 `onConnected(reconnected=true)` 让上层重订阅 |
| 1102 | 恢复，只更新 facts |
| 2104/2106/2158 | `facts.farms` 记 OK；2103/2105 记 BROKEN；2107/2108 记 INACTIVE（正常） |
| 带 reqId 的 error | 该请求 `fail(RequestRejectedException(code, msg))` |

账户：`trader.ibkr.account` 可选；配置了但不在 `managedAccounts` 里 → 状态 ERROR（不可重试，必须人工核对）。

## 5. 富途适配器（trader-gateway-futu）

| 类 | 职责 |
| --- | --- |
| `FutuChannel implements Transport` | 一个通道一个实例：`QOT`（`FTAPI_Conn_Qot`）与 `TRD`（`FTAPI_Conn_Trd`）。每次 `open()` **新建** SDK 连接对象（不复用 close 过的实例），`setClientInfo(clientInfo, 1)`、`setConnSpi`、`encrypt=true` 时 `setRSAPrivateKey(私钥文件内容)`，`initConnect(host, port, encrypt)`；就绪 = `onInitConnect(errCode==0)`；`onDisconnect` → 报告断线。`close()` = `FTAPI_Conn.close()` |
| `FutuQotSpi implements FTSPI_Qot` / `FutuTrdSpi implements FTSPI_Trd` | `onReply_*` 按 `nSerialNo` 交给该通道的 `FutuReplyRegistry`；`onPush_*` 交给推送分发器（本期只记录 `onPush_Notify`，第 2 期接行情推送） |
| `FutuReplyRegistry` | `seq → PendingReply<R>`，`reply-timeout` 默认 10 秒；`retType != 0` → `RequestRejectedException(retType/errCode, retMsg)` |
| `FutuRateLimits` | 按接口名的滑动窗口限流：`get-global-state 60/30s`、`get-acc-list 10/30s`（保守值，可配置） |
| `FutuGateway implements BrokerGateway` | `connect()` 同时拉起两个通道，**两个都就绪才算 CONNECTED**；`probe()` 在 QOT 通道 `getGlobalState`，把 `serverVer/serverBuildNo/qotLogined/trdLogined/programStatus/marketUS` 写进 facts，`programStatus != Ready` 或 `qotLogined=false` 时 detail 给出原因；`accounts()` 在 TRD 通道 `getAccList(needGeneralSecAccount=true)`，过滤 `SecurityFirm_FutuSecurities`，`trdEnv` Real→LIVE / Simulate→PAPER，`trdMarketAuthList` 映射到 `Market`（HK=1、US=2，其它忽略） |

- `FTAPI.init()` 进程内一次（静态守卫）；应用关闭时 `FTAPI.unInit()`。
- SDK 回调线程上不做任何阻塞，Future 完成同样转到 `futu-dispatch` 单线程。
- 本期不解锁交易、不订阅账户推送。

## 6. 通用限流器（trader-common）

`org.jdkxx.trader.common.ratelimit`：`RateLimiter` 接口 + `SlidingWindowRateLimiter(maxCalls, window, minInterval)`（时钟与休眠可注入）与 `TokenBucketRateLimiter(ratePerSecond, burst)`。`acquire()` 阻塞至可放行，超过 `maxWait` 抛 `RateLimitExceededException`。调用方永远是我们自己的服务线程，不会是 SDK 回调线程。

## 7. 核心、存储与应用层

- **trader-core**：`GatewayRegistry`（`Broker → BrokerGateway`）；`GatewayLifecycle implements SmartLifecycle`——启动时对 `enabled && auto-connect` 的网关调用 `connect()`（异步，**网关不可达不影响应用启动**），关闭时 `disconnect()`；`GatewayEventRecorder implements GatewayListener`——把连接事件写日志并落 `gateway_event` 表。
- **trader-storage**：`V2__gateway_event.sql`：`gateway_event(id bigserial PK, broker varchar(8), event varchar(16) /*CONNECTED|RECONNECTED|DISCONNECTED|ERROR*/, detail text, occurred_at timestamptz default now())` + `GatewayEventRepository`（插入、最近 N 条）。用途：事后能看到盈透每日重启、隧道抖动的时间线。
- **trader-app** REST（同源、无鉴权、只监听回环）：

| 接口 | 说明 |
| --- | --- |
| `GET /api/gateways` | 两家网关的 `GatewayStatus` 视图（含 facts、心跳时刻、重连次数） |
| `GET /api/gateways/{broker}` | 单个 |
| `POST /api/gateways/{broker}/connect` / `disconnect` | 手工控制（运维与调试用） |
| `GET /api/gateways/{broker}/accounts` | 账户列表，账户号脱敏（`U1*****`） |
| `GET /api/gateways/ibkr/instruments?symbol=AAPL` | `InstrumentInfo` 列表（合约查询） |
| `GET /api/gateways/events?limit=50` | 最近连接事件 |

- 健康指标 `gateways`：所有已启用网关都 CONNECTED（或没有启用的）→ UP；否则自定义状态 **DEGRADED**（HTTP 200，排序在 OUT_OF_SERVICE 与 UP 之间），这样 `bin/trader.sh start` 的就绪判断不会因为网关暂时不可达而失败，同时巡检能一眼看出降级。
- `/api/system/info` 的 gateways 段复用新的状态视图。
- **前端**：系统页的网关表改为每 5 秒自动刷新，显示状态、心跳、重连次数、facts、账户数；加连接/断开按钮；下方列出最近 20 条连接事件。仍是一个页面。

## 8. 配置

```yaml
trader:
  ibkr:
    enabled: false              # 本机在 config/secrets.yml 里置 true（host/port/client-id 也在那里）
    auto-connect: true          # 启用后随应用启动连接
    connect-timeout: 10s
    request-timeout: 15s
    heartbeat-interval: 30s
    heartbeat-timeout: 10s
    reconnect: { initial-delay: 5s, max-delay: 60s, max-attempts: -1 }
    message-rate-per-second: 40
  futu:
    enabled: false
    auto-connect: true
    client-info: trading-signal   # 生产；开发 trading-signal-dev；集成测试 trading-signal-it
    encrypt: false                # true 时 rsa-private-key-file 指向 PKCS#1 私钥（路径放 secrets）
    reply-timeout: 10s
    health-interval: 30s
    reconnect: { initial-delay: 5s, max-delay: 60s, max-attempts: -1 }
    limits: { get-global-state: 60/30s, get-acc-list: 10/30s }
```

入库的 `config/application.yml` 两家都保持 `enabled: false`，这样没有 secrets 的新克隆也能启动；`secrets.yml.example` 增加 `enabled: true` 的示例行。

## 9. 测试

- **单元**：`ConnectionSupervisor` 状态机与退避（假调度器/假时钟）、两个 Registry（完成/失败/超时/重复回调）、限流器、`IbkrWrapper` 分发（假 registry）、账户与合约映射、健康指标、事件仓储（连 `db_trader_dev` 的仓储测试归入集成）。
- **集成**（`-Dtrader.integration=true`，连接参数从环境变量 `TRADER_IBKR_HOST/PORT`、`TRADER_IBKR_TEST_CLIENT_ID`（用 91）、`TRADER_FUTU_HOST/PORT` 读取，缺失则跳过）：
  - `IbkrGatewayIT`：connect → `currentTime` 与本机时间差 < 60 秒 → `accounts()` ≥ 1 → `lookup(AAPL)` 返回 conId 与 minTick → disconnect。
  - `FutuGatewayIT`：connect → facts 里 `programStatus=Ready`、`qotLogined=true` → `accounts()` 非空且含 US 权限 → disconnect。
  - `ReconnectIT`：测试内起一个本地 TCP 中继指向真实网关，网关连中继；断开中继 → 观察 RECONNECTING；恢复中继 → 观察 CONNECTED 与 `reconnected=true` 事件。**实测优先**：重连逻辑只靠单元测试不够。
- **手工验收**：本机 `secrets.yml` 启用两家 → `run-local.sh` → 系统页两家 CONNECTED、心跳时刻在跳、`gateway_event` 有记录；`stop` 后事件表出现 DISCONNECTED。

## 10. 验收标准

1. `./mvnw clean verify` 通过；集成测试在本机对真实网关全部通过。
2. 应用在网关不可达时照常启动，状态显示 RECONNECTING 且按退避重试；网关恢复后自动 CONNECTED。
3. 系统页与 `/api/gateways` 正确反映状态、心跳、账户数（脱敏）、facts。
4. 日志与接口输出不出现主机、端口、完整账户号。

## 11. 风险与说明

- 盈透每日自动重启、每周重新认证会造成可预期的 RECONNECTING 时段；事件表让它可见，不算故障。
- IB Gateway 上 `primaryExch` 用 `NASDAQ` 还是 `ISLAND` 由集成测试给出结论，写进 CLAUDE.md。
- 富途 SDK 对 `FTAPI_Conn` 实例的复用语义未文档化，因此每次连接新建实例。
- 本期不触碰富途行情额度与解锁，不会产生任何交易或订阅副作用。

## 12. 待拍板

1. `gateway_event` 审计表本期做（建议做）。
2. REST 上的 connect / disconnect 写操作保留（建议保留：只监听回环，运维调试很有用）。
3. 健康指标语义：启用但未连接 → DEGRADED（HTTP 200）（建议）。
4. `reqContractDetails` 合约查询本期做（建议做，第 2/3 期都要用 minTick 与交易时段）。
5. 集成测试用 IB client-id 91、富途 clientInfo `trading-signal-it`。
6. 断线重连用本地 TCP 中继做集成测试（建议做）。
7. `GatewayState` 增加 `RECONNECTING`（建议）。
