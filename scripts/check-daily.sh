#!/usr/bin/env bash
#
# 收盘后的日线数据巡检：调应用的审计接口（只读，不直接连库）。
#   ./scripts/check-daily.sh [baseUrl] [date]
#   默认 baseUrl=http://127.0.0.1:8093（生产端口；本机开发实例用 8083），date 默认最近应有收盘 K 的交易日。
# 退出码：0 通过；1 关键项失败；2 接口不可达。
set -uo pipefail
BASE="${1:-http://127.0.0.1:8093}"
DATE="${2:-}"
URL="$BASE/api/bars/audit"
[[ -n "$DATE" ]] && URL="$URL?date=$DATE"
BODY="$(curl -sf --max-time 120 "$URL")" || { echo "❌ 审计接口不可达：$URL"; exit 2; }
echo "$BODY" | python3 -c '
import sys, json
r = json.load(sys.stdin)
print(f"日线数据审计 {r[\"date\"]}  →  {\"✅ 通过\" if r[\"ok\"] else \"❌ 未通过\"}   ({r[\"generatedAt\"]})")
print("  汇总:", ", ".join(f"{k}={v}" for k, v in r["summary"].items()))
for c in r["checks"]:
    flag = "✅" if c["ok"] else ("❌" if c["critical"] else "⚠️")
    print(f"  {flag} {c[\"name\"]:<14} {c[\"detail\"]}")
    for s in c.get("samples", [])[:10]:
        print(f"       - {s}")
sys.exit(0 if r["ok"] else 1)
'
