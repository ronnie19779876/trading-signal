#!/usr/bin/env bash
#
# 生产实例的启停。工作目录是发布包根目录（bin/ config/ lib/ logs/）。
#
#   bin/trader.sh start | stop | restart | status | tail
#
# stop 走 POST /actuator/shutdown：先排空在途请求，再关闭上下文、执行 @PreDestroy（断网关、停线程池、
# 释放连接池），最后 JVM 正常退出。脚本本身不对进程发信号；systemd 的 TimeoutStopSec 是最后兜底。
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

is_running() {
    [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null
}

start() {
    if is_running; then echo "已在运行，pid $(cat "$PID_FILE")"; return 0; fi
    local java; java="$(java_bin)"
    local ver; ver="$("$java" -version 2>&1 | head -1)"
    [[ "$ver" == *'"21.'* ]] || { echo "需要 JDK 21，当前：$ver（可在 config/trader.env 里 export JAVA_HOME）" >&2; exit 1; }
    echo "启动 trading-signal（:$(app_port)）..."
    nohup "$java" ${JAVA_OPTS:-} \
        -DsocksNonProxyHosts='localhost|127.*|[::1]' \
        -jar "$APP_JAR" \
        --spring.config.additional-location="file:./$CONFIG_DIR/" \
        > "$CONSOLE_LOG" 2>&1 &
    echo $! > "$PID_FILE"
    local url="http://127.0.0.1:$(app_port)/actuator/health"
    for ((i = 0; i < START_TIMEOUT; i++)); do
        if curl -sf "$url" 2>/dev/null | grep -q '"UP"'; then echo "已就绪：$url"; return 0; fi
        is_running || { echo "进程已退出，看 $CONSOLE_LOG" >&2; exit 1; }
        sleep 1
    done
    echo "超过 ${START_TIMEOUT}s 仍未就绪，看 $CONSOLE_LOG" >&2
    exit 1
}

stop() {
    if ! is_running; then echo "未在运行"; rm -f "$PID_FILE"; return 0; fi
    local pid; pid="$(cat "$PID_FILE")"
    echo "停止 trading-signal（pid $pid）—— 调用 /actuator/shutdown ..."
    curl -s -X POST "http://127.0.0.1:$(app_port)/actuator/shutdown" >/dev/null || true
    for ((i = 0; i < STOP_TIMEOUT; i++)); do
        kill -0 "$pid" 2>/dev/null || { rm -f "$PID_FILE"; echo "已停止"; return 0; }
        sleep 1
    done
    echo "超过 ${STOP_TIMEOUT}s 仍未退出（pid $pid）。脚本不强杀；请查看日志后自行处置" >&2
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
