#!/usr/bin/env bash
#
# Starts and stops the production instance. The working directory is the package root (bin/ config/ lib/ logs/).
#
#   bin/trader.sh start | stop | restart | status | tail
#
# stop goes through POST /actuator/shutdown: in-flight requests drain first, then the context closes and
# runs @PreDestroy (disconnect the gateways, stop thread pools, release the connection pool), and finally
# the JVM exits normally. The script itself never signals the process; systemd's TimeoutStopSec is the
# last resort.
# config/trader.env (secrets, not in the repository) is sourced with set -a, so its values reach the JVM as
# environment variables and Spring's relaxed binding maps them (TRADER_IBKR_HOST -> trader.ibkr.host).
# "Running" means the PID in the PID file is alive AND its command line contains lib/trader-app.jar
# (see is_running).
set -euo pipefail
cd "$(dirname "$0")/.."

APP_JAR="lib/trader-app.jar"
CONFIG_DIR="config"
PID_FILE="logs/trading-signal.pid"
CONSOLE_LOG="logs/console.log"
START_TIMEOUT=90
STOP_TIMEOUT=40

mkdir -p logs
[[ -f "$CONFIG_DIR/trader.env" ]] && set -a && source "$CONFIG_DIR/trader.env" && set +a

java_bin() {
    if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then echo "$JAVA_HOME/bin/java"; else echo java; fi
}

app_port() {
    grep -m1 -E '^[[:space:]]*port:' "$CONFIG_DIR/application.yml" | sed -E 's/[^0-9]//g'
}

# Whether the given PID is this package's JVM. kill -0 alone was not enough: after a crash or reboot
# the stale PID file can name an unrelated process that reused the number, and then start refused to
# start ("已在运行"), status reported running and stop waited on a stranger (found in the 2026-10-31
# review). Checking the command line for the jar path rules that out.
is_app_pid() {
    local pid="$1" args
    [[ "$pid" =~ ^[0-9]+$ ]] || return 1
    # Captured first rather than piped into grep -q: under pipefail an early-exiting grep can turn a
    # match into a SIGPIPE failure of ps.
    args="$(ps -p "$pid" -o args= 2>/dev/null)" || return 1
    [[ "$args" == *"$APP_JAR"* ]]
}

is_running() {
    [[ -f "$PID_FILE" ]] && is_app_pid "$(cat "$PID_FILE")"
}

# Whether /actuator/health reports the instance ready. The HTTP code says nothing here: application.yml
# sets status.http-mapping (DEGRADED: 200), which replaces Spring's default mapping, so DOWN answers 200
# too. And with show-details the body holds every component's own "status":"UP", so grepping for "UP"
# anywhere passed while the overall status was DOWN (found in the 2026-10-31 review). Only the top-level
# status counts; DEGRADED (a gateway temporarily unreachable) is ready on purpose, it must not fail a
# start (see GatewaysHealthIndicator).
health_ready() {
    local body
    body="$(curl -s --max-time 5 "$1" 2>/dev/null)" || return 1
    if command -v python3 >/dev/null 2>&1; then
        printf '%s' "$body" | python3 -c 'import json, sys; sys.exit(0 if json.load(sys.stdin).get("status") in ("UP", "DEGRADED") else 1)' 2>/dev/null
    else
        # Spring writes the top-level status as the first member
        [[ "$body" =~ ^[[:space:]]*\{[[:space:]]*\"status\"[[:space:]]*:[[:space:]]*\"(UP|DEGRADED)\" ]]
    fi
}

start() {
    if is_running; then echo "已在运行，pid $(cat "$PID_FILE")"; return 0; fi
    local java; java="$(java_bin)"
    local ver; ver="$("$java" -version 2>&1 | head -1)"
    [[ "$ver" == *'"21.'* ]] || { echo "需要 JDK 21，当前：${ver}（可在 config/trader.env 里 export JAVA_HOME）" >&2; exit 1; }
    echo "启动 trading-signal（:$(app_port)）..."
    nohup "$java" ${JAVA_OPTS:-} \
        -DsocksNonProxyHosts='localhost|127.*|[::1]' \
        -jar "$APP_JAR" \
        --spring.config.additional-location="file:./$CONFIG_DIR/" \
        > "$CONSOLE_LOG" 2>&1 &
    local pid=$!
    echo "$pid" > "$PID_FILE"
    local url="http://127.0.0.1:$(app_port)/actuator/health"
    for ((i = 0; i < START_TIMEOUT; i++)); do
        if health_ready "$url"; then echo "已就绪：$url"; return 0; fi
        # kill -0, not is_running: this is the child we just forked, and for a moment after the fork its
        # command line is still this script's, not java's
        kill -0 "$pid" 2>/dev/null || { echo "进程已退出，看 $CONSOLE_LOG" >&2; exit 1; }
        sleep 1
    done
    echo "超过 ${START_TIMEOUT}s 仍未就绪，看 $CONSOLE_LOG" >&2
    exit 1
}

stop() {
    if ! is_running; then echo "未在运行"; rm -f "$PID_FILE"; return 0; fi
    local pid; pid="$(cat "$PID_FILE")"
    echo "停止 trading-signal（pid ${pid}）—— 调用 /actuator/shutdown ..."
    # X-Trader-Client: since 2.0.2 the server answers 403 to write requests without this header
    curl -s -X POST -H 'X-Trader-Client: script' "http://127.0.0.1:$(app_port)/actuator/shutdown" >/dev/null || true
    for ((i = 0; i < STOP_TIMEOUT; i++)); do
        is_app_pid "$pid" || { rm -f "$PID_FILE"; echo "已停止"; return 0; }
        sleep 1
    done
    echo "超过 ${STOP_TIMEOUT}s 仍未退出（pid ${pid}）。脚本不强杀；请查看日志后自行处置" >&2
    exit 1
}

status() {
    if is_running; then
        echo "运行中，pid $(cat "$PID_FILE")"
        curl -s "http://127.0.0.1:$(app_port)/actuator/health" || true; echo
    else
        echo "未运行"
    fi
}

case "${1:-}" in
    start) start ;;
    stop) stop ;;
    restart) stop; start ;;
    status) status ;;
    tail) tail -f "$CONSOLE_LOG" ;;
    *) echo "用法: $0 start|stop|restart|status|tail" >&2; exit 2 ;;
esac
