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
