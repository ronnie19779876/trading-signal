#!/usr/bin/env bash
#
# 收盘后巡检：日线审计 + 基本面审计 + 作业与网关健康。三段都只读，不触发任何跑批。
#   ./scripts/check-daily.sh [baseUrl] [date]
#   默认 baseUrl=http://127.0.0.1:8093（生产端口；本机开发实例用 8083），date 默认最近应有收盘 K 的交易日。
# 退出码：0 全部通过；1 有关键项失败或健康降级；2 接口不可达。
#
# 为什么三段一起跑：此前脚本只调日线审计，基本面审计写好了却没人跑——
# 而估值被跳过这类问题恰好只有基本面审计能发现。
set -uo pipefail
BASE="${1:-http://127.0.0.1:8093}"
DATE="${2:-}"
SUFFIX=""
[[ -n "$DATE" ]] && SUFFIX="?date=$DATE"
FAILED=0

audit() {
  local title="$1" url="$2" body
  body="$(curl -sf --max-time 120 "$url")" || { echo "❌ $title 接口不可达：$url"; exit 2; }
  echo "$body" | python3 -c '
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

# 健康：作业跑批与网关。DEGRADED 也算问题——作业失败或被跳过就落在这里。
HEALTH="$(curl -sf --max-time 30 "$BASE/actuator/health")" || { echo "❌ 健康接口不可达"; exit 2; }
echo "$HEALTH" | python3 -c '
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

exit $FAILED
