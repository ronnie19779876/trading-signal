#!/usr/bin/env bash
#
# Starts and stops the local development instance. The working directory must be the repository root; the
# external configuration is read from ./config (config/application.yml + config/secrets.yml).
#
#   ./scripts/run-local.sh          start in the foreground (build the jar first: ./mvnw -pl trader-app -am -DskipTests package)
#   ./scripts/run-local.sh stop     stop -- POST /actuator/shutdown; the script contains no kill at all
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/jdk.sh

CONFIG_YML="config/application.yml"
APP_JAR="trader-app/target/trader-app.jar"
STOP_TIMEOUT=40

app_port() {
    # Relies on server.port being the first port: in the file (the datasource port is inside the URL, so
    # it never appears first)
    grep -m1 -E '^[[:space:]]*port:' "$CONFIG_YML" | sed -E 's/[^0-9]//g'
}

start() {
    require_jdk21
    [[ -f "$APP_JAR" ]] || { echo "没有 ${APP_JAR}，先构建：$(mvn_cmd) -pl trader-app -am -DskipTests package" >&2; exit 1; }
    [[ -f config/secrets.yml ]] || echo "提示：没有 config/secrets.yml（可从 secrets.yml.example 复制）；数据库密码将尝试从 ~/.pgpass 读取"
    echo "启动开发实例（:$(app_port)，JDK ${JAVA_HOME}）..."
    # -DsocksNonProxyHosts is mandatory: with a system-wide SOCKS proxy enabled on this Mac, pgjdbc sends the
    # unresolved loopback address through the proxy and fails with UnknownHostException: 127.0.0.1 --
    # psql/curl work fine, only Java cannot reach the local database.
    exec "$JAVA_HOME/bin/java" \
        -DsocksNonProxyHosts='localhost|127.*|[::1]' \
        -jar "$APP_JAR" \
        --spring.config.additional-location=file:./config/
}

stop() {
    local port url code pid port_closed=false
    port="$(app_port)"
    [[ -n "$port" ]] || { echo "从 $CONFIG_YML 解析不出端口" >&2; exit 1; }
    url="http://127.0.0.1:$port/actuator/shutdown"
    # The HTTP port closes before the @PreDestroy hooks (gateway disconnects, pool shutdown) have run,
    # so a closed port is not a stopped instance: until 3.5.2 "已停止" was printed while the JVM was
    # still shutting down, and a rebuild or restart right after it raced the old process (found in the
    # 2026-10-31 review). Remember the listening PID now, while the port still points at it, and wait
    # for that process to be gone. Only observed, never signalled.
    pid="$(lsof -nP -t -iTCP:"${port}" -sTCP:LISTEN 2>/dev/null | head -1 || true)"
    echo "停止开发实例（:${port}${pid:+，pid ${pid}}）—— 调用 $url ..."
    # X-Trader-Client: the server answers 403 to write requests without this header (LocalRequestGuardFilter).
    code="$(curl -s -o /dev/null -w '%{http_code}' -X POST -H 'X-Trader-Client: script' "$url" || true)"
    case "$code" in
        000) echo "没有实例在监听 :$port"; exit 0 ;;
        200) ;;
        *) echo "关停请求没被接受（HTTP ${code}）：确认外置配置开了 shutdown 端点" >&2; exit 1 ;;
    esac
    for ((i = 0; i < STOP_TIMEOUT; i++)); do
        if $port_closed || ! curl -s "http://127.0.0.1:$port/actuator/health" >/dev/null 2>&1; then
            port_closed=true
            if [[ -z "${pid}" ]]; then echo "已停止（端口已关；没取到进程号，未确认进程退出）"; exit 0; fi
            ps -p "${pid}" >/dev/null 2>&1 || { echo "已停止"; exit 0; }
        fi
        sleep 1
    done
    if $port_closed; then
        echo "超过 ${STOP_TIMEOUT}s：端口已关但进程 ${pid} 未退出，请看日志后自行处置" >&2
    else
        echo "超过 ${STOP_TIMEOUT}s 端口仍开着，请自行检查" >&2
    fi
    exit 1
}

case "${1:-start}" in
    start) start ;;
    stop) stop ;;
    *) echo "用法: $0 [start|stop]" >&2; exit 2 ;;
esac
