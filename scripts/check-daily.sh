#!/usr/bin/env bash
#
# Post-close daily check: daily-bar audit + fundamentals audit + account audit + signal audit + selection
# audit + job and gateway health. All six sections are read-only and trigger no batch job.
#   ./scripts/check-daily.sh [baseUrl] [date]
#   Default baseUrl=http://127.0.0.1:8093 (production port; the local dev instance uses 8083); date defaults
#   to the latest trading day that should already have a closing daily bar.
#   Best run after 19:00 US/Eastern (account snapshot 18:00, signal evaluation 18:10); when run earlier, a
#   missing snapshot or evaluation for the day is only reported, not judged a failure.
# Exit codes: 0 all passed; 1 a critical check failed, an endpoint returned an error, or health is degraded;
# 2 an endpoint could not be reached.
#
# Why the sections run together: the script used to call only the daily-bar audit, and the fundamentals
# audit, although written, was never run -- yet problems such as skipped valuations are exactly what only
# the fundamentals audit can catch.
# Likewise, **one failing section must not kill the ones after it**: before 3.1.1 the script used
# curl -sf + exit 2, and -f also returns non-zero for HTTP 500, so "an audit endpoint returned 500" was
# reported as "endpoint unreachable" and the script exited on the spot, skipping the remaining four
# sections. "Cannot connect" and "endpoint error" are now told apart; both are recorded and the run goes on.
set -uo pipefail
BASE="${1:-http://127.0.0.1:8093}"
DATE="${2:-}"
SUFFIX=""
[[ -n "$DATE" ]] && SUFFIX="?date=$DATE"
FAILED=0

UNREACHABLE=0
FETCH_BODY=""

# Fetches one URL: the body goes into the global FETCH_BODY; returns 0 on success, otherwise reports the
# error itself and returns non-zero.
# The body must **not** go to stdout for the caller to capture with $(fetch ...): that is a subshell, and
# the assignments to FAILED / UNREACHABLE made inside it never come back, so the flags would always stay 0
# (the first version of this change was written exactly that way).
# No curl -f: it returns the same non-zero code for 4xx/5xx and for "cannot connect", so it cannot tell
# "the service is up but the endpoint blew up" from "the service is not running". Instead the status code
# is taken with -w and the body with -o: code 000 means no connection.
# The third argument is one extra acceptable status code (used for the health endpoint, see below).
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
    echo "❌ $title 报错 HTTP ${code}：$url"
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
audit "量化选股审计" "$BASE/api/selection/audit$SUFFIX"

# Health: batch jobs and gateways. DEGRADED counts as a problem too -- a failed or skipped job shows up here.
# The HTTP code says nothing about health: application.yml sets status.http-mapping (DEGRADED: 200), which
# replaces Spring's default mapping, so every status -- DOWN included -- answers 200 (the comment here used
# to claim 503 for degraded / DOWN; corrected after the 2026-10-31 review). The verdict therefore comes from
# the body's top-level "status" only; components are listed for diagnosis. 503 is still accepted and parsed
# the same way in case the mapping is ever changed back to Spring's default.
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

# "Cannot connect" deserves to be told apart from "ran but failed" more than anything else, so 2 wins.
(( UNREACHABLE )) && exit 2
exit $FAILED
