#!/usr/bin/env python3
"""
生成 Postman 集合与环境文件（Collection v2.1）。接口清单只维护在下面的 ENDPOINTS 里，
新增/改动 REST 接口时改这里再重跑：

    python3 docs/postman/build_collection.py

产物：
    trading-signal.postman_collection.json          集合（含每个请求的断言）
    trading-signal.dev.postman_environment.json     本机开发实例（baseUrl 127.0.0.1:8083）
    trading-signal.prod.postman_environment.json    生产实例（经 SSH 隧道后的本地端口，默认 8093）

⚠️ 环境文件里只有 baseUrl 与示例参数，不放任何密码、主机名、账户号。生成的 JSON 不要手改。
"""
import json
import os

OUT_DIR = os.path.dirname(os.path.abspath(__file__))
NAME = "trading-signal"

# 通用断言片段
T_200 = ['pm.test("HTTP 200", () => pm.response.to.have.status(200));']
T_JSON = ['const body = pm.response.json();']

ENDPOINTS = [
    {
        "folder": "系统",
        "items": [
            {
                "name": "系统信息",
                "method": "GET",
                "path": "/api/system/info",
                "desc": "版本、环境、数据库、两家网关状态、AI 配置状态。所有字段可公开展示。",
                "tests": T_200 + T_JSON + [
                    'pm.test("有版本与环境", () => { pm.expect(body.version).to.be.a("string"); pm.expect(body.environment).to.be.oneOf(["DEV","PROD","未声明"]); });',
                    'pm.test("两家网关", () => { pm.expect(body.gateways).to.have.lengthOf(2); pm.expect(body.gateways[0].broker).to.eql("IBKR"); pm.expect(body.gateways[1].broker).to.eql("FUTU"); });',
                    'pm.test("数据库已启用且标记与环境一致", () => { pm.expect(body.database.enabled).to.eql(true); pm.expect(body.database.marker).to.eql(body.environment); });',
                ],
            },
        ],
    },
    {
        "folder": "网关",
        "items": [
            {
                "name": "网关状态列表",
                "method": "GET",
                "path": "/api/gateways",
                "desc": "两家网关的状态视图：state / detail / 心跳 / 重连次数 / facts。",
                "tests": T_200 + T_JSON + [
                    'pm.test("两家网关", () => pm.expect(body).to.have.lengthOf(2));',
                    'body.forEach(g => pm.test(`${g.broker} 状态合法：${g.state}`, () => pm.expect(g.state).to.be.oneOf(["DISABLED","DISCONNECTED","CONNECTING","CONNECTED","RECONNECTING","ERROR"])));',
                    'body.filter(g => g.enabled).forEach(g => pm.test(`${g.broker} 已启用则应 CONNECTED（当前 ${g.state}：${g.detail}）`, () => pm.expect(g.state).to.eql("CONNECTED")));',
                    'body.filter(g => g.state === "CONNECTED").forEach(g => pm.test(`${g.broker} 心跳在 90 秒内`, () => pm.expect(Date.now() - Date.parse(g.lastHeartbeatAt)).to.be.below(90000)));',
                    'pm.test("facts 不含主机/端口/账户号", () => body.forEach(g => Object.values(g.facts).forEach(v => { pm.expect(String(v)).to.not.match(/\\b(\\d{1,3}\\.){3}\\d{1,3}\\b/); pm.expect(String(v)).to.not.match(/\\bU\\d{7,8}\\b/); })));',
                ],
            },
            {
                "name": "单个网关状态",
                "method": "GET",
                "path": "/api/gateways/{{broker}}",
                "desc": "broker 取 ibkr / futu（环境变量 broker）。",
                "tests": T_200 + T_JSON + [
                    'pm.test("broker 匹配", () => pm.expect(body.broker.toLowerCase()).to.eql(pm.environment.get("broker").toLowerCase()));',
                ],
            },
            {
                "name": "账户列表（脱敏）",
                "method": "GET",
                "path": "/api/gateways/{{broker}}/accounts",
                "desc": "已连接时返回账户数组，账户号脱敏为前两位 + *****；未连接返回 503 GATEWAY_NOT_CONNECTED。",
                "tests": [
                    'pm.test("200 或 503", () => pm.expect(pm.response.code).to.be.oneOf([200, 503]));',
                    'if (pm.response.code === 200) { const body = pm.response.json(); pm.test("至少一个账户且已脱敏", () => { pm.expect(body.length).to.be.above(0); body.forEach(a => pm.expect(a.maskedId).to.match(/^.{2}\\*{5}$/)); }); }',
                    'if (pm.response.code === 503) { pm.test("未连接的错误码", () => pm.expect(pm.response.json().code).to.eql("GATEWAY_NOT_CONNECTED")); }',
                ],
            },
            {
                "name": "合约查询（盈透）",
                "method": "GET",
                "path": "/api/gateways/ibkr/instruments",
                "query": [{"key": "symbol", "value": "{{symbol}}"}],
                "desc": "reqContractDetails：返回 conId、名称、primaryExchange、minTick、时区、交易时段。查无此标的返回 []。",
                "tests": [
                    'pm.test("200 或 503", () => pm.expect(pm.response.code).to.be.oneOf([200, 503]));',
                    'if (pm.response.code === 200) { const body = pm.response.json(); pm.test("有合约且带 conId 与 minTick", () => { pm.expect(body.length).to.be.above(0); pm.expect(body[0].brokerRef).to.be.a("string"); pm.expect(body[0].minTick).to.be.above(0); }); }',
                ],
            },
            {
                "name": "合约查询（查无此标的）",
                "method": "GET",
                "path": "/api/gateways/ibkr/instruments",
                "query": [{"key": "symbol", "value": "ZZZZNOSUCH"}],
                "desc": "盈透错误 200（No security definition）映射为空数组。",
                "tests": [
                    'pm.test("200 或 503", () => pm.expect(pm.response.code).to.be.oneOf([200, 503]));',
                    'if (pm.response.code === 200) { pm.test("空数组", () => pm.expect(pm.response.json()).to.eql([])); }',
                ],
            },
            {
                "name": "合约查询（富途不支持 → 400）",
                "method": "GET",
                "path": "/api/gateways/futu/instruments",
                "query": [{"key": "symbol", "value": "AAPL"}],
                "desc": "本期只有盈透实现参考数据端口。",
                "tests": [
                    'pm.test("HTTP 400", () => pm.response.to.have.status(400));',
                    'pm.test("PARAM_INVALID", () => pm.expect(pm.response.json().code).to.eql("PARAM_INVALID"));',
                ],
            },
            {
                "name": "手工断开",
                "method": "POST",
                "path": "/api/gateways/{{broker}}/disconnect",
                "desc": "断开并停止重连。断开后看「网关状态列表」应为 DISCONNECTED，/actuator/health 为 DEGRADED（HTTP 仍 200）。",
                "tests": T_200 + T_JSON + [
                    'pm.test("状态为 DISCONNECTED 或 DISABLED", () => pm.expect(body.state).to.be.oneOf(["DISCONNECTED","DISABLED"]));',
                ],
            },
            {
                "name": "手工连接",
                "method": "POST",
                "path": "/api/gateways/{{broker}}/connect",
                "desc": "发起连接（异步）。几秒后看「网关状态列表」应回到 CONNECTED；未启用的网关返回 400。",
                "tests": [
                    'pm.test("200 或 400（未启用）", () => pm.expect(pm.response.code).to.be.oneOf([200, 400]));',
                    'if (pm.response.code === 200) { pm.test("状态为 CONNECTING/RECONNECTING/CONNECTED", () => pm.expect(pm.response.json().state).to.be.oneOf(["CONNECTING","RECONNECTING","CONNECTED"])); }',
                ],
            },
            {
                "name": "最近连接事件",
                "method": "GET",
                "path": "/api/gateways/events",
                "query": [{"key": "limit", "value": "20"}],
                "desc": "gateway_event 表最近 N 条：CONNECTED / RECONNECTED / DISCONNECTED / ERROR。",
                "tests": T_200 + T_JSON + [
                    'pm.test("是数组", () => pm.expect(body).to.be.an("array"));',
                    'body.forEach(e => pm.test(`事件类型合法：${e.broker} ${e.event}`, () => pm.expect(e.event).to.be.oneOf(["CONNECTED","RECONNECTED","DISCONNECTED","ERROR"])));',
                ],
            },
            {
                "name": "未知券商 → 400",
                "method": "GET",
                "path": "/api/gateways/xyz",
                "desc": "参数错误的统一响应 { code, message }。",
                "tests": [
                    'pm.test("HTTP 400", () => pm.response.to.have.status(400));',
                    'pm.test("PARAM_INVALID", () => pm.expect(pm.response.json().code).to.eql("PARAM_INVALID"));',
                ],
            },
        ],
    },
    {
        "folder": "行情（第 2 期）",
        "items": [
            {"name": "覆盖统计 + 额度 + 运行中的作业", "method": "GET", "path": "/api/bars/coverage",
             "desc": "K 线行数/标的数/最早最新交易日、全量/池/持仓规模、历史额度、运行中的作业。",
             "tests": T_200 + T_JSON + ['pm.test("有 rows 与 quota", () => { pm.expect(body.rows).to.be.a("number"); pm.expect(body.quota).to.exist; });']},
            {"name": "日线数据审计（收盘后必查）", "method": "GET", "path": "/api/bars/audit",
             "desc": "默认审最近一个应有收盘 K 的交易日：完整性（全量∪池∪持仓当天都有 K 线）、字段合理性、前收连续性（漏日）、复权因子新鲜度、同步错误、增量作业、网关。ok=true 才算过。",
             "tests": T_200 + T_JSON + ['pm.test("审计通过 ok=true（失败时看 checks）", () => pm.expect(body.ok, JSON.stringify(body.checks.filter(c => !c.ok))).to.eql(true));']},
            {"name": "交易日历回补（异步作业）", "method": "POST", "path": "/api/bars/calendar/backfill",
             "desc": "券商只能给到约 2016-09（实测请求 21 年与 27 年返回相同），更早的从池/持仓/基准的日 K 线反推。幂等。",
             "tests": T_200 + T_JSON + ['pm.test("有 jobId", () => pm.expect(body.jobId).to.be.a("number"));']},
            {"name": "交易日历", "method": "GET", "path": "/api/bars/calendar", "query": [{"key": "from", "value": "2026-01-01"}, {"key": "to", "value": "2026-12-31"}],
             "desc": "交易日列表；source=FUTU 券商给的，DERIVED 从日 K 线反推。",
             "tests": T_200 + T_JSON + ['pm.test("是数组且来源合法", () => { pm.expect(body).to.be.an("array"); body.forEach(d => pm.expect(d.source).to.be.oneOf(["FUTU","DERIVED"])); });']},
            {"name": "历史额度", "method": "GET", "path": "/api/bars/quota", "desc": "富途历史 K 线额度（7 天滚动）。",
             "tests": T_200 + T_JSON + ['pm.test("used/remain", () => { pm.expect(body.used).to.be.a("number"); pm.expect(body.remain).to.be.a("number"); });']},
            {"name": "同步成分股（异步作业）", "method": "POST", "path": "/api/universe/sync",
             "desc": "Wikipedia 标普 500 + 纳指 100 → instrument / index_constituent，SPY 交叉核对，富途静态信息解析。返回 jobId；已有作业在跑 → 409。",
             "tests": ['pm.test("200 或 409", () => pm.expect(pm.response.code).to.be.oneOf([200, 409]));',
                       'if (pm.response.code === 200) { pm.environment.set("jobId", pm.response.json().jobId); }']},
            {"name": "作业列表", "method": "GET", "path": "/api/jobs", "query": [{"key": "limit", "value": "10"}],
             "desc": "running = 当前作业与进度；recent = job_run 最近 N 条。",
             "tests": T_200 + T_JSON + ['pm.test("recent 是数组", () => pm.expect(body.recent).to.be.an("array"));']},
            {"name": "作业详情", "method": "GET", "path": "/api/jobs/{{jobId}}", "desc": "按 id 看状态与摘要。",
             "tests": ['pm.test("200 或 404", () => pm.expect(pm.response.code).to.be.oneOf([200, 404]));']},
            {"name": "全量标的列表", "method": "GET", "path": "/api/universe", "query": [{"key": "index", "value": "SP500"}],
             "desc": "index=SP500|NDX100，role=POOL|HOLDING 可筛选。",
             "tests": T_200 + T_JSON + ['pm.test("是数组", () => pm.expect(body).to.be.an("array"));']},
            {"name": "单个标的", "method": "GET", "path": "/api/universe/{{symbol}}", "desc": "含所属指数、行业、池角色、K 线覆盖。",
             "tests": ['pm.test("200 或 404", () => pm.expect(pm.response.code).to.be.oneOf([200, 404]));']},
            {"name": "加入标的池（触发深度回补）", "method": "POST", "path": "/api/pool/{{symbol}}", "query": [{"key": "role", "value": "POOL"}],
             "desc": "role=POOL|HOLDING。加入后自动排深度回补作业（20 年，占 1 个历史额度）；池满或作业冲突 → 409。",
             "tests": ['pm.test("200 / 404 / 409", () => pm.expect(pm.response.code).to.be.oneOf([200, 404, 409]));']},
            {"name": "标的池", "method": "GET", "path": "/api/pool", "desc": "POOL + HOLDING 及各自的 K 线覆盖。",
             "tests": T_200 + T_JSON + ['pm.test("是数组", () => pm.expect(body).to.be.an("array"));']},
            {"name": "移出标的池", "method": "DELETE", "path": "/api/pool/{{symbol}}", "desc": "K 线保留，只移除池成员。",
             "tests": ['pm.test("200 或 404", () => pm.expect(pm.response.code).to.be.oneOf([200, 404]));']},
            {"name": "全量轮转拉 K 线（异步，约 8 分钟）", "method": "POST", "path": "/api/bars/refresh/universe", "query": [{"key": "count", "value": "1000"}],
             "desc": "订阅 → getKL(count) → 反订阅，零历史额度。count=1000 首拉、10 增量。",
             "tests": ['pm.test("200 或 409", () => pm.expect(pm.response.code).to.be.oneOf([200, 409]));']},
            {"name": "深度回补一只（异步，占额度）", "method": "POST", "path": "/api/bars/backfill/{{symbol}}",
             "desc": "requestHistoryKL 2006 年起分页 + 复权因子。",
             "tests": ['pm.test("200 / 404 / 409", () => pm.expect(pm.response.code).to.be.oneOf([200, 404, 409]));']},
            {"name": "深度回补所有待补的池/持仓（异步）", "method": "POST", "path": "/api/bars/backfill", "desc": "按额度依次回补，额度用尽即停并标 PARTIAL。",
             "tests": ['pm.test("200 或 409", () => pm.expect(pm.response.code).to.be.oneOf([200, 409]));']},
            {"name": "复权因子刷新（异步；all=true 全量约 5 分钟）", "method": "POST", "path": "/api/bars/rehab/refresh", "query": [{"key": "all", "value": "false"}],
             "desc": "all=false：池/持仓 + 全量里 7 天以上未刷新的；all=true：全量 518 只。限频 60/30s，不占历史额度。",
             "tests": ['pm.test("200 或 409", () => pm.expect(pm.response.code).to.be.oneOf([200, 409]));']},
            {"name": "每日增量（异步）", "method": "POST", "path": "/api/bars/increment", "desc": "刷新交易日历 → 补缺口 → 刷新复权因子。",
             "tests": ['pm.test("200 或 409", () => pm.expect(pm.response.code).to.be.oneOf([200, 409]));']},
            {"name": "K 线查询（不复权）", "method": "GET", "path": "/api/bars/{{symbol}}",
             "query": [{"key": "from", "value": "2026-08-01"}, {"key": "to", "value": "2026-09-03"}, {"key": "adjust", "value": "none"}],
             "desc": "adjust=none|forward|backward；默认最近 90 天。",
             "tests": ['pm.test("200 或 404", () => pm.expect(pm.response.code).to.be.oneOf([200, 404]));',
                       'if (pm.response.code === 200) { const body = pm.response.json(); pm.test("按日期升序", () => { for (let i = 1; i < body.length; i++) pm.expect(body[i].tradeDate > body[i-1].tradeDate).to.be.true; }); }']},
            {"name": "K 线查询（前复权）", "method": "GET", "path": "/api/bars/{{symbol}}",
             "query": [{"key": "from", "value": "2026-08-01"}, {"key": "to", "value": "2026-09-03"}, {"key": "adjust", "value": "forward"}],
             "desc": "读取层按复权因子计算。",
             "tests": ['pm.test("200 或 404", () => pm.expect(pm.response.code).to.be.oneOf([200, 404]));']},
            {"name": "复权因子", "method": "GET", "path": "/api/bars/{{symbol}}/rehab", "desc": "requestRehab 落库的因子列表。",
             "tests": ['pm.test("200 或 404", () => pm.expect(pm.response.code).to.be.oneOf([200, 404]));']},
            {"name": "取消当前作业", "method": "POST", "path": "/api/jobs/cancel", "desc": "只是置取消标志，作业在下一批边界停下。",
             "tests": T_200},
        ],
    },
    {
        "folder": "实时报价（第 2 期·步骤 2）",
        "items": [
            {"name": "订阅状态", "method": "GET", "path": "/api/quotes/status",
             "desc": "期望/已订数量、额度、推送统计、SSE 客户端数。",
             "tests": T_200 + T_JSON + ['pm.test("有 subscribed 与 totalPushes", () => { pm.expect(body.subscribed).to.be.a("number"); pm.expect(body.totalPushes).to.be.a("number"); });']},
            {"name": "订阅对账（订池与持仓）", "method": "POST", "path": "/api/quotes/subscriptions/reconcile",
             "desc": "期望 = 池 ∪ 持仓；新增订阅、多余反订阅（未满 1 分钟延后）。网关未连接时 error 字段给出原因。",
             "tests": T_200 + T_JSON + ['pm.test("有 desired/subscribed", () => { pm.expect(body.desired).to.be.a("number"); pm.expect(body.subscribed).to.be.a("number"); });']},
            {"name": "全部报价快照", "method": "GET", "path": "/api/quotes", "desc": "缓存里的最新报价（有效价按时段取）。",
             "tests": T_200 + T_JSON + ['pm.test("是数组", () => pm.expect(body).to.be.an("array"));', 'body.forEach(q => pm.test(`${q.instrument.symbol} 时段合法`, () => pm.expect(q.session).to.be.oneOf(["PRE","RTH","AFTER","OVERNIGHT","CLOSED"])));', 'body.forEach(q => pm.test(`${q.instrument.symbol} 参考价与时段一致`, () => pm.expect(q.referenceClose).to.eql(["PRE","AFTER","OVERNIGHT"].includes(q.session) ? q.rthPrice : q.lastClose)));']},
            {"name": "单个报价", "method": "GET", "path": "/api/quotes/{{symbol}}", "desc": "未订阅或未收到推送 → 404。",
             "tests": ['pm.test("200 或 404", () => pm.expect(pm.response.code).to.be.oneOf([200, 404]));']},
            {"name": "暂停订阅（释放额度）", "method": "POST", "path": "/api/quotes/subscriptions/pause", "desc": "反订阅全部并清空缓存。", "tests": T_200},
            {"name": "恢复订阅", "method": "POST", "path": "/api/quotes/subscriptions/resume", "desc": "重新对账。", "tests": T_200},
        ],
    },
    {
        "folder": "基本面（第 2 期·步骤 3）",
        "items": [
            {"name": "基本面审计（收盘后必查）", "method": "GET", "path": "/api/fundamentals/audit",
             "desc": "默认审最近一个应有收盘数据的交易日：估值完整性、合理性（负市盈率是亏损股的真实数据，不算错）、池与持仓的财报陈旧度、估值作业。休市日直接判过。",
             "tests": T_200 + T_JSON + ['pm.test("审计通过 ok=true（失败时看 checks）", () => pm.expect(body.ok, JSON.stringify(body.checks.filter(c => !c.ok))).to.eql(true));']},
            {"name": "覆盖情况", "method": "GET", "path": "/api/fundamentals/coverage",
             "desc": "最新估值日期、当天有估值的标的数、财报期数、池里有财报的只数。",
             "tests": T_200 + T_JSON + ['pm.test("有 targets 与 reports", () => { pm.expect(body.targets).to.be.a("number"); pm.expect(body.reports).to.be.a("number"); });']},
            {"name": "单只概览", "method": "GET", "path": "/api/fundamentals/{{symbol}}",
             "desc": "最新估值 + 最近 4 期主要指标 + 公司简介。库里没有这个代码 → 404。",
             "tests": T_200 + T_JSON + ['pm.test("有 symbol", () => pm.expect(body.symbol).to.be.a("string"));']},
            {"name": "估值序列", "method": "GET", "path": "/api/fundamentals/{{symbol}}/valuation", "query": [{"key": "from", "value": "2026-08-01"}, {"key": "to", "value": "2026-09-30"}],
             "desc": "估值时间序列，默认最近 90 天。亏损股的市盈率为负是真实数据。",
             "tests": T_200 + T_JSON + ['pm.test("是数组", () => pm.expect(body).to.be.an("array"));']},
            {"name": "财务报表", "method": "GET", "path": "/api/fundamentals/{{symbol}}/reports", "query": [{"key": "statement", "value": "main_index"}, {"key": "limit", "value": "8"}],
             "desc": "statement 取 income / balance_sheet / cash_flow / main_index。注意年报与四季报期末可能同一天，靠 periodText 区分；财年可能领先自然年。",
             "tests": T_200 + T_JSON + ['pm.test("是数组", () => pm.expect(body).to.be.an("array"));']},
            {"name": "估值快照刷新（异步作业）", "method": "POST", "path": "/api/fundamentals/valuation/refresh",
             "desc": "全量 ∪ 池 ∪ 持仓，一次 400 只，不占订阅与历史额度。返回 jobId；已有作业在跑 → 409。",
             "tests": T_200 + T_JSON + ['pm.test("有 jobId", () => pm.expect(body.jobId).to.be.a("number"));']},
            {"name": "财报刷新（异步作业）", "method": "POST", "path": "/api/fundamentals/financials/refresh", "query": [{"key": "all", "value": "false"}],
             "desc": "all=false 只做池与持仓（约 80 秒，带公司简介）；all=true 做全量成分股（518 只约 41 分钟，不取简介）。返回 jobId。",
             "tests": T_200 + T_JSON + ['pm.test("有 jobId", () => pm.expect(body.jobId).to.be.a("number"));']},
        ],
    },
    {
        "folder": "Actuator",
        "items": [
            {
                "name": "健康检查",
                "method": "GET",
                "path": "/actuator/health",
                "desc": "总状态 UP；有网关启用但未连接时为 DEGRADED（HTTP 仍 200），明细在 components.gateways。",
                "tests": T_200 + T_JSON + [
                    'pm.test("状态 UP 或 DEGRADED", () => pm.expect(body.status).to.be.oneOf(["UP","DEGRADED"]));',
                    'pm.test("有 gateways 与 db 组件", () => { pm.expect(body.components.gateways).to.exist; pm.expect(body.components.db.status).to.eql("UP"); });',
                ],
            },
            {
                "name": "构建信息",
                "method": "GET",
                "path": "/actuator/info",
                "desc": "build-info：版本与构建时间。",
                "tests": T_200 + T_JSON + ['pm.test("有版本", () => pm.expect(body.build.version).to.be.a("string"));'],
            },
        ],
    },
]


def request(item):
    path = item["path"]
    url = {
        "raw": "{{baseUrl}}" + path + ("?" + "&".join(f'{q["key"]}={q["value"]}' for q in item.get("query", [])) if item.get("query") else ""),
        "host": ["{{baseUrl}}"],
        "path": [p for p in path.strip("/").split("/")],
    }
    if item.get("query"):
        url["query"] = item["query"]
    return {
        "name": item["name"],
        "request": {
            "method": item["method"],
            "header": [{"key": "Accept", "value": "application/json"}],
            "url": url,
            "description": item.get("desc", ""),
        },
        "event": [{"listen": "test", "script": {"type": "text/javascript", "exec": item.get("tests", [])}}],
        "response": [],
    }


def build_collection():
    return {
        "info": {
            "name": NAME,
            "description": "trading-signal REST 接口。先选环境（dev / prod），变量：baseUrl、broker（ibkr|futu）、symbol。"
                           "所有请求只读或只影响网关连接状态，不涉及交易。集合由 docs/postman/build_collection.py 生成，勿手改。",
            "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
        },
        "item": [{"name": f["folder"], "item": [request(i) for i in f["items"]]} for f in ENDPOINTS],
        "variable": [],
    }


def build_environment(name, base_url):
    return {
        "name": f"{NAME} {name}",
        "values": [
            {"key": "baseUrl", "value": base_url, "enabled": True},
            {"key": "broker", "value": "ibkr", "enabled": True},
            {"key": "symbol", "value": "AAPL", "enabled": True},
            {"key": "jobId", "value": "1", "enabled": True},
        ],
        "_postman_variable_scope": "environment",
    }


def write(name, data):
    path = os.path.join(OUT_DIR, name)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print("wrote", path)


if __name__ == "__main__":
    write(f"{NAME}.postman_collection.json", build_collection())
    write(f"{NAME}.dev.postman_environment.json", build_environment("dev", "http://127.0.0.1:8083"))
    write(f"{NAME}.prod.postman_environment.json", build_environment("prod", "http://127.0.0.1:8093"))
