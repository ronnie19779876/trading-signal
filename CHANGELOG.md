# CHANGELOG

## 3.0.4（2026-09-19 发布）

- 仪表盘持仓表与盈透 App 对齐（对照 App 截图逐项核过，见 ARCHITECTURE §20.6）：加回涨跌（最新价 − 前收，前收取盈透行情）与涨跌 %、成本（Cost Basis）、占组合（% of Portfolio），
  按 App 同口径计算；最新价显示两位小数。`/api/account/live` 的 `positions[]` 新增 `priorClose`、`change`、`changePct`、`costBasis`、`portfolioPct`。
- 不切换 reqAccountUpdates：实测不比账户汇总快，且持仓市值与 App 对不上。
- 修复：当日盈亏可能一直显示"等待盈透推送"。3.0.3 一律丢弃 reqPnL 首条，而逐只盈亏稳定后再订时 reqPnL 只推一条且是对的；
  改为用盈透逐只当日 / 浮盈之和核对推送是否完整，对得上即采用（显示仍是 reqPnL 原值）。

## 3.0.3（2026-09-19 发布）

- 实时账户**只给盈透原值、不做折算**（用户核对后要求与盈透 App 一致），见 ARCHITECTURE §20.5：净值取盈透 NetLiquidation（去掉估算）；
  当日 / 浮动盈亏只用盈透账户盈亏（去掉逐只加总兜底，没有时显示"等待盈透推送"）；持仓现价改为订盈透行情取最新价（不再用市值 ÷ 数量，实测两者不同）；
  去掉当日 %、盈亏率、占净值与"较收盘快照"。reqPnL 加 10 秒看门狗重订（生产盘后实测第二条推送可能一直不来）。
  **接口变化**：`/api/account/live` 去掉 `nav` 与 `pnl.source`，`positions[].price` 换成 `last` / `lastAt` / `lastDelayed`；Postman 集合要重新导入。

## 3.0.2（2026-09-19 发布）

**升级注意**：生产此前是 3.0.0，**3.0.1 没有单独部署，内容随本版一起上线**（AI 额度按角色分配、账本 AI 分组、前端第 1、2 期）。
无迁移、无配置变更；外置配置与 `trader.env` 沿用旧目录的。Postman 集合要重新导入（新增实时账户）。
实时账户按需订阅：生产实例占用自己 client-id 下的 1 个账户汇总订阅（每客户端上限 2，每日快照的一次性请求用另 1 个）。

- 实时账户：`GET /api/account/live`（盈透常驻订阅，按需开、闲置 5 分钟关；实时净值 = 现金 + 应计股息 + 逐只市值之和），设计与实测见 ARCHITECTURE §20。
  网关层新增常驻订阅通道与 `LiveAccountGateway` 端口；Postman 集合要重新导入。
- 前端仪表盘：布局参考 futu-trader（账户五格、净值走势含实时虚线、候选标的与持仓两栏），账户与持仓优先用实时数据；
  菜单改为仪表盘 / 持仓 / 账户 / 基本面 / 入场信号 / 交易 / 系统（持仓、交易为占位），按钮样式；全站改美股口径绿涨红跌；页头加美东时段灯。

## 3.0.1（2026-09-19 发布）

3.0.0 上线后首个四巫日（美东 2026-09-18）暴露的两处缺陷，加前端改造第 1、2 期。设计见 ARCHITECTURE §19。

**升级注意**：无迁移、无配置变更、无接口变更；Postman 集合不用重新导入。部署后纸面账本的 AI 分组自动按新口径重算。

- 修复：AI 额度按字母发放。评估作业边评估边调模型、额度先到先得，而评估顺序是成分股的字母序——09-18 51 条候选，
  每日上限 20 在 G 开头用完，持仓 IBKR 被预算跳过。改为持仓 → 池 → 池外，同一角色内按代码。
- 修复：纸面账本把"没有模型结论"算成"模型放行"。预算跳过、失败等也会写分析行并挂到信号上，分组只看有没有挂——
  09-18 那批显示放行 50 / 否决 2 / 无结论 0，实为 19 / 2 / 31。改为按那次分析的裁决分组。
- 修复：信号详情抽屉在挂着没有结论的分析时隐藏"手工分析"按钮。现在写明当晚没有结论的原因，优先展示事后手工补的意见，
  没有时给按钮并显示今天的用量，额度用完置灰。
- 前端第 1 期：品牌 T-Signal 与图标；满宽布局，页头常驻环境徽标、当前作业与主题切换；暗色主题（跟随系统 + 手动覆盖）；
  公共格式化与组件；Element Plus 中文；不存在的路径显示 404 页而不是白屏。
- 前端第 2 期：首页改仪表盘；系统信息移到 `/system`，网关明细改抽屉。

## 3.0.0（2026-09-17 发布）

第 4 期「入场信号」，设计与实测见 ARCHITECTURE §18。

**升级注意**：
- 三个迁移 V10（`rehab_factor` 补比例列，并清空带合股 / 送股 / 转增事件标的的复权刷新时间，下一次增量多刷这些标的）、
  V11（每日评估、信号、纸面账本）、V12（模型分析）；只加列、加表。
- 外置配置 `config/application.yml` 新增 `trader.ai.signal-veto-enabled: true`（模板已带）；服务器上改过的项要合进新模板。
- `trader.env` 需要 `OPENAI_API_KEY`；没配时照常出信号，每个候选记一行"未配置"，信号审计提示。
- 新跑批：美东 18:10 信号评估、22:00 信号补偿检查；`check-daily.sh` 第五段信号审计，建议美东 19:00 之后跑。
- Postman 集合要重新导入（新增信号与 AI 两组接口）。

- 步骤 1：入场哨兵四门判定（判据版本 `sentinel-v1`，沿用 futu-trader entry-v3 的判据与阈值，独立实现）；
  结构口径（价格调拆股、合股、送股、分拆、特别股息，成交量只按股数比例调）；数据层检查（剔除非交易日 K 线、陈旧、缺日、口径换算失败）；
  只读接口 `GET /api/signals/evaluate/{symbol}`、`GET /api/signals/replay/{symbol}`。开发库回放 MSFT / ISRG / MU 信号数与 entry-v3 分享稿逐一相同。
- V10：`rehab_factor` 补存合股、送股、转增比例；已有这三类事件的标的在下一次增量里优先重拉复权因子。
- 步骤 2：与 futu-trader 研究缓存逐日对账（7 只约 3 万个判定日，信号日期逐条相同，TSM 金样本逐项吻合）；
  修两处口径：支撑区回看边界改为 t − j ≤ 252（与 entry-v3 一致）、均线比较的真平局不再因浮点噪声判成大于。
  纸面交易 `PaperTrade`；回放接口加 `trades` / `stopAtr` / `half`；回放统计脚本 `scripts/research/sentinel_report.py`
  （结论：入场判据没有显示出择时价值，收益来自池的选择与出场规则，见 ARCHITECTURE §18.6）。
- 步骤 3：V11 三张表（每日评估、信号、纸面账本）；评估作业 `SIGNAL_EVALUATION`（美东 18:10，22:00 补偿），
  边沿 / 冷却 / 过期、输入指纹、两个出场变体的账本每天整段重算；接口 `POST /api/signals/evaluate`、`GET /api/signals`、
  `GET /api/signals/{id}`、`POST /api/signals/{id}/status`、`GET /api/signals/evaluations`、`/evaluations/{symbol}`、`/ledger`、`/audit`；
  `check-daily.sh` 加第五段信号审计；`jobs` 健康指标监控信号评估。
- 步骤 4：模型第二意见（只有否决权）。V12 `ai_analysis`；输入构建（技术面、静态市盈率 5 年分位、财报白名单、公司简介、财报窗口提示，
  不含持仓与账户）、提示词 `sentinel-veto-v1`、Responses API 结构化输出、证据逐条核对、否决规则（看空 + 把握非 LOW + ≥2 条核对通过的看空证据）；
  评估作业对候选调用，否决的信号记 `VETOED`；每日上限、作业内时长预算、同一输入复用；补跑不调。
  接口 `GET /api/signals/ai-input/{symbol}`、`POST|GET /api/ai/analyses`、`GET /api/ai/analyses/{id}`、`GET /api/ai/usage`；
  账本汇总按模型裁决分组；信号审计加 `aiAnalyses`。**升级注意**：生产 `trader.env` 需配 `OPENAI_API_KEY`，外置配置打开 `trader.ai.signal-veto-enabled`。
- 步骤 5：前端"信号"页（`/signals`）：状态条、今日评估（四门漏斗）、信号（状态操作、详情抽屉含 K 线价位线与 AI 多空证据、手工分析）、纸面账本、回放。
  新增 `GET /api/signals/bars/{symbol}`（折回判定日价格尺度的 K 线）；`KlineChart` 支持标记、价位线与区间；信号与账本查询改为批量、列表不取判定明细。

## 2.0.2（2026-09-14 发布）

全量代码审查后的修复，设计与实测见 ARCHITECTURE §17。

**升级注意**：写接口从此必须带请求头 `X-Trader-Client`（`Host` 只认回环）。前端、`bin/trader.sh`、`scripts/run-local.sh`
已带；Postman 集合要重新导入；手工 curl 加 `-H 'X-Trader-Client: cli'`。`bin/trader.sh` 必须随包一起更新。

- 数据：盘中触发的回补 / 轮转不再写入当天未收盘的 K 线，已写入的由下一次增量自动重拉，日线审计加 `unsettledBars`；
  估值快照只在收盘窗口内取，窗口外手工刷新 409；开发实例跑轮转后不再自动订阅实时报价；环境守卫改为先校验后迁移。
- 盈透：握手无应答时状态机不再挂死（自建带超时的 socket，开关连接不碰 SDK 的锁）；会话各自绑定回调，重连中不重复建连；
  心跳等待者超时出队；受管账户列表缺失时不再放行。
- 富途：历史 K 线翻页不再占着回复线程等限流；回复注册表按会话隔离、只收当前连接的回复，K 线回复核对标的；
  私钥报错不带路径且不再无限重试。状态机：SDK 回调转调度线程，不可重试错误进入 ERROR，刚就绪即断开不算连上。
- 防护：新增 `LocalRequestGuardFilter`（挡本机浏览器的跨站 POST 与 DNS rebinding）；`check-secrets.sh` 修掉四处静默漏报
  （IPv4 与明文口令两个模式此前从未生效）。
- 其它：HOLDING 同步在只剩现金管理工具时照常执行；systemd 去掉 `ExecReload`；`package.sh` 拒绝 SNAPSHOT；
  `.gitignore` 排除私钥文件；API 文档、Postman 集合、前端类型三处同步补齐，集合的写操作单独成组。

## 2.0.1（2026-09-14 发布）

- 修：前端深链接 404。前端用 history 路由，后端原先没有回退，直接打开或刷新 `/account`、`/marketdata`、`/fundamentals`
  都是 404（从首页点进去正常，第 0 期起就有）。新增 `SpaForwardController`：单段、不含点、不是 api / actuator / error 的路径
  转发给 index.html。`SpaForwardControllerTest` 核对前端路由表里的每个路径都能深链接打开。

## 2.0.0（2026-09-14 发布）

第 3 期「账户与持仓」，设计与实测见 ARCHITECTURE §16。

- 步骤 1：新增 `AccountGateway` 端口（持仓、资金汇总，只读）与盈透实现。持仓走 `reqPositionsMulti`、资金走一次性
  `reqAccountSummary`，都是收齐即取消；汇总剔除明文账户号（`$LEDGER-AccountOrGroup`）；同一账户并发的汇总请求合并成一次。
  `IbkrAccountsTest` 9 例（账户号剔除与无效数量报错两处已反证），`IbkrGatewayIT` 新增只读账户用例并对真实网关跑通。
- 步骤 2：账户快照与对账。V9 新增 `account_snapshot`、`position_snapshot`、`instrument.ibkr_con_id` 与 `pool_member.source / return_role`。
  作业 `ACCOUNT_SNAPSHOT` 美东 18:00 工作日、21:00 补拍；快照窗口交易日 16:15 至次日 04:00，窗口外手工拍 409，`force=true` 仅开发环境。
  持仓按 conId（首次按代码，空格→点）映射；价格取当日 K 线，库里没有的用富途快照价兜底（`ValuationSnapshot` 新增 `lastPrice`）。
  对账四项：资金恒等式、市值、持仓集合与 HOLDING、非股票持仓。账户号库里只存带密钥的 HMAC（`trader.account.key-secret`，外置配置）。
  接口 `POST /api/account/snapshot`、`GET /api/account/snapshots/latest`、`GET /api/account/snapshots`；`jobs` 健康指标纳入账户快照（逾期 120 小时）。
  碰撞重试抽成 `ScheduledSubmitter`，行情与账户调度共用；`ScheduledPlaceholdersTest` 覆盖账户调度。
  开发实例实测：8 条持仓零缺价，恒等式差 0.00，市值对账差 0.0028%。
- 步骤 3：持仓自动维护池里的 HOLDING（`HoldingSyncService`）。持有而池里没有的加入（来源 IBKR）、POOL 升 HOLDING 并记下原角色、
  清仓后原为 POOL 的回 POOL、其余移出池（数据保留）；BENCHMARK 不动；现金管理工具与非美股不算；返回空持仓而池里有 HOLDING 时不执行。
  触发：快照作业里先同步再对账、生产实例盈透连上 60 秒后、`POST /api/account/holdings/sync?apply=false|true`。
  手工 `POST /api/pool/{symbol}?role=HOLDING` 改为 409，前端下拉禁用 HOLDING；新增或升级的标的自动排深度回补。
  对账的持仓集合项把持有的基准算作一致。开发实例实测：加入 IBKR 并回补到 20 年，快照对账四项全 OK，再同步无需变动。
- 步骤 4：账户审计 `GET /api/account/audit`，进 `check-daily.sh` 第三段（美东 18:30 前、或刚启用还没有任何快照时，缺快照只提示；对账 FAIL 为关键项）；
  最新快照附日变化 `change`（净值变化含出入金、上一份数量 × 价差，当天有买卖时标近似）；前端新增"账户"页
  （净值、日变化、净值走势、对账、持仓与价格来源、持仓同步计划与执行、账户审计）。
- 修（第 2 期步骤 2 遗留）：池变动钩子触发的实时订阅对账不看 `auto-subscribe`，开发实例上加减池成员会让开发实例也订阅、
  与生产同时订。改为 `QuoteSubscriptionService.onPoolChanged`：`auto-subscribe=false` 且当前没有订阅时不对账。
- 数据：SPY 由 HOLDING 改为 BENCHMARK（生产与开发库均已改），标普基准不再跟着买卖走。

## 1.2.0（2026-09-14 发布）

补齐第 2 期观察期发现的两处（都不是故障，机制兜住了，但留着会周期性变慢、会让一道防线失明）。
开发实例实测（开发库 518 只全部同一天到期）：刷 125 只 = 池 21 + 到期最久的 104 只，其余 396 只顺延，耗时 70 秒；库里核对数字一致。

- 复权因子不再集中到期：全量标的到期的按最久未刷优先，每次增量最多刷全量的 1/`refresh.rehab-spread-days`（默认 5，约 105 只）。
  此前 521 只同一天刷过，7 天后在同一次增量里一起到期，多花约 300 秒，2026-09-10 让增量跑了 691 秒、挤掉估值作业的时点。
  池、持仓、基准仍每天刷。`RehabSpreadTest` 模拟八周：每次 ≤105 只，同一只两次刷新间隔 ≤14 天。
- 当天补偿检查每次运行都写一行 `job_run`（作业名 `CATCHUP_CHECK`，含非交易日与数据齐全）；检查本身出错记 FAILED。
  `jobs` 健康指标纳入监控：最近一次 FAILED 或跑过却 96 小时没成功（`catchupOverdue`）即降级。
  此前数据齐全时只打日志，它自身停摆库里毫无痕迹。

## 1.1.1（2026-09-12 发布）

- 修：`GET /api/pool` 按角色写死枚举了 POOL 与 HOLDING，加 `BENCHMARK` 时漏改，
  导致基准标的（QQQ）在库里存在、被正常采集与订阅，却不出现在接口返回与前端列表里。
  改为遍历 `PoolRole.values()`；前端角色下拉补上 BENCHMARK。
- 新增 `PoolRoleCoverageTest` 守住这条：接口不得按角色写死，前端下拉必须覆盖全部取值。

## 1.1.0（2026-09-12 发布）

第 2 期步骤 3~5：基本面数据、基准标的与交易日历、作业可靠性与数据订正。
已通过 2026-09-10 至 09-12 的生产观察期：三个交易日数据零异常，7 个定时作业全部成功。

### 第 2 期·步骤 5：定时作业可靠性

- 调度碰撞不再静默丢作业：每 5 分钟重试、最多 6 次；彻底放弃时写 `job_run` 的 `SKIPPED` 留痕（V8）。
  此前增量与估值只隔 10 分钟而增量实测要 6.5 分钟，一旦超时估值被直接丢弃，而估值是时点数据当天不补就永远没有。
- 新增当天补偿检查（美东 21:00，早于次日盘前）：缺 K 线或估值就补跑，触发方式记 `CATCHUP`。
- 新增 `jobs` 健康指标：任一定时作业 FAILED / SKIPPED / 逾期未跑 → DEGRADED。
- 幽灵 K 线订正 `POST /api/bars/cleanup/phantom?apply=false`（默认试跑）与审计提示项 `phantomBars`：
  券商在美股假日给过脏 K 线（SPY 三根，成交额 0，独立日那根凭空造出 15% 日内暴跌）。
- `SpringBeanConstructorTest`：禁止 Spring 组件有多个未标注的公开构造器
  （`JobsHealthIndicator` 曾因此让生产启动失败——该 bean 只在开了跑批的实例装配，本地发现不了）。
- 日志按天滚动保留 14 天（`logback-spring.xml`），此前只有不滚动的 nohup 重定向且随版本目录清理丢失。
- `scripts/check-daily.sh` 合并三段：日线审计 + 基本面审计 + 运行健康。此前只调日线审计，
  恰好漏掉唯一能发现估值缺失的检查。

### 第 2 期·步骤 4：基准标的与交易日历（开发中）

- 新增 `BENCHMARK` 角色（V6/V7）：基准照常采集日 K、复权、实时订阅，但不参与选股（`UniverseScope.candidates()` 排除）。
- 交易日历回补作业 `CALENDAR_BACKFILL`：券商段（实测只能回到 2016-09）+ 更早的从池/持仓/基准的日 K 线反推，
  `trading_day.source` 区分来源；`GET /api/bars/calendar`、`POST /api/bars/calendar/backfill`、覆盖视图与审计项。
- 审计新增 `historyGaps`（最近 90 天对照日历的缺口，提示项）与 `GET /api/bars/gaps`（全历史深扫）。
  实测发现富途的 SPY 缺 26 个交易日（2009~2012）而前收连续性检查查不出来——券商自己的前收与缺口自洽。
- 反推要求一天≥2 只标的同时成交：实测富途给 SPY 在三个美股假日留了脏 K 线，只取并集会把假日算成交易日。

### 第 2 期·步骤 3：基本面数据（进行中）

- 第 1 步网关层：`MarketDataGateway` 增加 `snapshots` / `financials` / `companyProfile`（富途快照、四类财务报表、公司简介），
  新增三个限流名与 `FutuFundamentals` 映射器；领域新增 `ValuationSnapshot` / `FinancialReport` / `FinancialStatement` / `CompanyProfile`。
- 存储层 V5：`valuation_snapshot`、`financial_report`（唯一键带期别）、`financial_item`（长表）、`instrument.profile`。
- 核心层：`VALUATION_SNAPSHOT`（全量每交易日，一次 400 只）与 `FINANCIALS_REFRESH`（池与持仓每周，四类报表）两个作业、
  基本面审计、查询服务；`/api/fundamentals/*` 七个接口、Postman 与前端 API 同步。
- `POST /api/fundamentals/financials/refresh?all=true` 支持全量成分股回补（518 只约 41 分钟，不取公司简介）。
- 修正：富途把美股 REITs 也归为 Trust（标普 500 里 25 只），按"ETF 没财报"跳过会漏掉它们；改为不按类型过滤，
  审计也区分"有财报但过旧"与"从来没有财报（基金正常）"。
- 报价新增 `referenceClose`（涨跌基准）：常规时段为券商昨收，盘前盘后夜盘为上一个常规时段收盘。
  此前前端把券商 `lastClose` 当昨收显示，盘前时它比真实基准早一个交易日，读者自算的涨跌幅与显示值对不上。
- 修：`valuation-cron` / `financials-cron` 只写在配置记录的 `@DefaultValue` 上、没进 jar 内 `application.yml`，
  开发实例调度关闭发现不了，生产（调度开启）启动即失败。补齐 yaml，并加 `ScheduledPlaceholdersTest` 守住这条。
- 前端「基本面」页：估值概览与序列、四类财务报表（字段按券商返回动态成列）、公司简介、作业触发。
- 开发实例对真实网关跑通：520 只估值零失败、19 只个股 889 期财报零失败、20 只公司简介，审计通过。
- 对真实 OpenD 实测确认取值口径：估值字段是 proto required（判空无效）、亏损股市盈率为负、
  美股 ETF 净值多数缺失以 0 占位、四类报表字段编号不跨表通用；并修正了设计里年报与四季报期末同日导致的唯一键冲突。

- 审计接口识别休市日：显式传入非交易日时回"当天休市"并判通过，不再误报 520 只缺 K 线（日历覆盖不到的旧日期照常审计）。
- 盈透同一错误码持续复现时日志降频：首次照常告警，之后每 10 分钟汇总一条并带上被压掉的次数，连上后复位。一次 25 小时的网关停机曾刷出 1500 多行同样的 502。

## 1.0.0-SNAPSHOT（开发中）

### 第 2 期·步骤 2：实时报价订阅，不落库（2026-09-03）

- 领域 `Quote` / `MarketSession` / `SubscriptionInfo`；`MarketDataGateway` 增加 subscribeQuotes / unsubscribeQuotes / subscriptionInfo / addQuoteListener（富途 Basic 推送 → dispatch 线程 → 监听器）。
- 有效价按时段取（实测盘前 curPrice 冻结、preMarket 更新）；时段由心跳的 marketUS 判定，拿不到按美东时钟。
- `QuoteSubscriptionService` 对账（池 ∪ 持仓；新增/延后反订阅；连上/重连/池变动/手工触发；暂停恢复）；与全量轮转的额度协调（默认轮转期间暂停）。
- `QuoteCache` + `QuoteStreamService`（SSE 每秒合并帧、15 秒状态）；`/api/quotes*`；前端实时报价表（EventSource）与 lightweight-charts 日 K 图。
- 配置 `trader.marketdata.realtime.*`（开发机 auto-subscribe=false，发布包 true）；Postman 新增「实时报价」目录。
- 测试：单元 87 个；集成 `FutuQuotesIT`。
- `GET /api/bars/audit` 日线数据审计与 `scripts/check-daily.sh`（收盘后巡检）；生产部署到服务器用户目录（`~/trading-signal` 软链）并完成首轮装载。
- 加入标的池时库里没有的代码（ETF、非成分股 ADR）先向富途解析静态信息并自动建档（SPY、TSM）。
- 收尾（数据质量核查后）：V4 `bar_sync_state.rehab_fetched_at`；`REHAB_REFRESH` 作业（`POST /api/bars/rehab/refresh`）；每日增量按 7 天到期刷新全量复权因子；覆盖统计增加 `rehabCovered`。

### 第 2 期·步骤 1：日 K 线行情底座（2026-09-03）

- 领域与端口：DailyBar / RehabFactor / TradingDay / InstrumentStatic / HistoryQuota / IndexCode / PoolRole / Adjustment；`MarketDataGateway`（富途实现，含分页历史、订阅取 K、复权因子、交易日历、额度）。
- 存储 V3：instrument、index_constituent（since/until）、pool_member、daily_bar（不复权）、rehab_factor、trading_day、bar_sync_state、job_run。
- 成分股：Wikipedia 标普 500 + 纳指 100 解析、SPY 持仓 xlsx 交叉核对、CSV 导入；富途静态信息解析（brokerId=0 判未解析）。
- K 线：全量订阅轮转（零历史额度）、池/持仓 20 年深度回补（额度守卫）、每日增量（交易日历缺口 + overlap）、读取层复权（实测定为逐事件复合 PER_EVENT）。
- 作业：单线程串行 `JobService` + job_run 记录 + 进度 + 取消；定时增量与周六成分股同步（可开关）。
- 接口 `/api/universe*`、`/api/pool*`、`/api/bars*`、`/api/jobs*`；错误映射新增 404 / 409；前端「行情」页；Postman 集合新增「行情」目录。
- 测试：单元 78 个；集成 `FutuMarketDataIT`（含复权语义判定）。

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
