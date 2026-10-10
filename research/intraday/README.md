# 日内可行性研究（第 0 期）

背景、预登记与结果都在 [ARCHITECTURE §24](../../docs/ARCHITECTURE.md)。这里只放可复现的代码；`data/` 不入库。

**纪律**：只读开发实例与开发库（`DevDb` 会核对 `app_environment` 必须是 DEV），富途只读历史 K 线；
预登记写定后不改，数据没取齐不出判定，不换定义重试。

## 运行

```bash
./mvnw -o -pl trader-domain -am -DskipTests compile     # 一次：研究代码直接调用领域层的同一套纯函数
./scripts/run-local.sh                                  # 开发实例（FreezeSignals 要调它的回放接口）
research/intraday/run.sh FreezeSignals                  # 冻结信号清单 → data/signals.csv、data/signals-freeze.txt
research/intraday/run.sh H2IntradayStop                 # H2：盘中止损（只用日 K）→ data/h2-signals.csv
research/intraday/run.sh MinuteFetch                    # H1 分钟线，每周最多新占 80 只额度，用完即停，下周重跑续取
research/intraday/run.sh H1EntryTiming                  # H1：入场时点；分钟线没取齐时只报缺多少
```

附带报告（深度标的 2008 ~ 2026）：

```bash
research/intraday/run.sh -Ddepth=HIST20Y -DreplayFrom=2007-10-01 -DwindowFrom=2008-01-02 -Dout=signals_deep FreezeSignals
research/intraday/run.sh H2IntradayStop data/signals_deep.csv
```

连接参数沿用应用的环境变量：`TRADER_DB_HOST` / `TRADER_DB_PORT` / `TRADER_DB_PASSWORD`（缺省走 `~/.pgpass`），
`TRADER_FUTU_HOST` / `TRADER_FUTU_PORT`（缺省读本机 `config/secrets.yml`）。

## 文件

| 文件 | 作用 |
| --- | --- |
| `Lab` | 只读 GET 开发实例、CSV、sha256 |
| `DevDb` | 只读开发库，行映射与 trader-storage 的仓库逐字段一致 |
| `FreezeSignals` | 冻结信号清单（回放 + 窗口过滤 + H1 抽样） |
| `IntradayStopTrade` | `PaperTrade.simulate` 的逐行副本 + 盘中初始止损开关 |
| `H2IntradayStop` | 两道复现关 + H2 判定 |
| `MinuteFetch` | 富途 1 分钟线下载（信号日对 + 同数量随机对照日对），额度守卫 |
| `H1EntryTiming` | 首根开盘关 + H1a / H1b 判定 + 对照 |
| `Stats` | 按信号日成组的自助法、98.3% 区间、门槛 +0.05R、两半同向 |
