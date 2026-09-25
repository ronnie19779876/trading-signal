#!/usr/bin/env bash
#
# 收盘后巡检：日线审计 + 基本面审计 + 账户审计 + 信号审计 + 作业与网关健康。五段都只读，不触发任何跑批。
#   ./scripts/check-daily.sh [baseUrl] [date]
#   默认 baseUrl=http://127.0.0.1:8093（生产端口；本机开发实例用 8083），date 默认最近应有收盘 K 的交易日。
#   建议美东 19:00 之后跑（账户快照 18:00、信号评估 18:10）；更早跑时当天缺快照或评估只提示，不判失败。
# 退出码：0 全部通过；1 有关键项失败、接口报错或健康降级；2 有接口连不上。
#
# 为什么几段一起跑：此前脚本只调日线审计，基本面审计写好了却没人跑——
# 而估值被跳过这类问题恰好只有基本面审计能发现。
# 同理，**一段出问题不能掐掉后面几段**：3.1.1 前用的是 curl -sf + exit 2，
# 而 -f 对 HTTP 500 也返回非零，于是「某个审计接口报 500」被打成「接口不可达」并当场退出，
# 后面四段一条都不跑。现在把「连不上」与「接口报错」分开，两种都记下来接着往下跑。
set -uo pipefail
BASE="${1:-http://127.0.0.1:8093}"
DATE="${2:-}"
SUFFIX=""
[[ -n "$DATE" ]] && SUFFIX="?date=$DATE"
FAILED=0

UNREACHABLE=0
FETCH_BODY=""

# 取回一个 URL：正文放进全局 FETCH_BODY，成功返回 0，失败自己报错并返回非零。
# 正文**不能**走 stdout 让调用方 $(fetch ...) 接——那是子 shell，里面对 FAILED / UNREACHABLE
# 的赋值出不来，标志位会永远是 0（本次改动第一版就是这么写的）。
# 不用 curl -f：它对 4xx/5xx 与连不上返回同一个非零码，区分不了「服务在但接口炸了」和「服务没起来」。
# 第三个参数是额外可接受的状态码（健康接口降级时返回 503，正文仍是完整 JSON）。
fetch() {
  local title="$1" url="$2" alsoOk="${3:-}" tmp code
  FETCH_BODY=""
  tmp="$(mktemp)"
  code="$(curl -s -o "$tmp" -w '%{http_code}' --max-time 120 "$url")"
  if [[ "$code" == "000" ]]; then
    echo "❌ $title 连不上：$url"
    UNREACHABLE=1
    rm -f "$tmp"; return 1
  fi
  if [[ "$code" != "200" && "$code" != "$alsoOk" ]]; then
    echo "❌ $title 报错 HTTP $code：$url"
    sed -n '1,5p' "$tmp" | sed 's/^/       /'
    FAILED=1
    rm -f "$tmp"; return 1
  fi
  FETCH_BODY="$(cat "$tmp")"
  rm -f "$tmp"
}

audit() {
  local title="$1" url="$2"
  fetch "$title" "$url" || { echo; return 0; }
  printf '%s' "$FETCH_BODY" | python3 -c '
import sys, json
title = sys.argv[1]
r = json.load(sys.stdin)
print("%s %s  →  %s   (%s)" % (title, r["date"], "✅ 通过" if r["ok"] else "❌ 未通过", r["generatedAt"]))
print("  汇总:", ", ".join("%s=%s" % (k, v) for k, v in r["summary"].items()))
for c in r["checks"]:
    flag = "✅" if c["ok"] else ("❌" if c["critical"] else "⚠️")
    print("  %s %-20s %s" % (flag, c["name"], c["detail"]))
    for s in c.get("samples", [])[:10]:
        print("       - %s" % s)
sys.exit(0 if r["ok"] else 1)
' "$title" || FAILED=1
  echo
}

audit "日线数据审计" "$BASE/api/bars/audit$SUFFIX"
audit "基本面审计"   "$BASE/api/fundamentals/audit$SUFFIX"
audit "账户审计"     "$BASE/api/account/audit$SUFFIX"
audit "信号审计"     "$BASE/api/signals/audit$SUFFIX"

# 健康：作业跑批与网关。DEGRADED 也算问题——作业失败或被跳过就落在这里。
# 降级时 actuator 返回 503，正文仍是完整 JSON，所以 503 照常解析。
if fetch "健康接口" "$BASE/actuator/health" 503; then
printf '%s' "$FETCH_BODY" | python3 -c '
import sys, json
h = json.load(sys.stdin)
overall = h.get("status")
print("运行健康  →  %s" % ("✅ " + overall if overall == "UP" else "❌ " + str(overall)))
for name, c in sorted(h.get("components", {}).items()):
    st = c.get("status")
    flag = "✅" if st == "UP" else "❌"
    print("  %s %-20s %s" % (flag, name, st))
    if st != "UP":
        for k, v in (c.get("details") or {}).items():
            print("       - %s: %s" % (k, v))
sys.exit(0 if overall == "UP" else 1)
' || FAILED=1
fi

# 连不上比「跑了但没过」更值得单独区分，优先用 2。
(( UNREACHABLE )) && exit 2
exit $FAILED
