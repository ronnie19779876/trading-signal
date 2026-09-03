#!/usr/bin/env bash
#
# 本机开发实例的启停。工作目录必须是仓库根，外置配置从 ./config 读取（config/application.yml + config/secrets.yml）。
#
#   ./scripts/run-local.sh          前台启动（先 ./mvnw -pl trader-app -am -DskipTests package 打出 jar）
#   ./scripts/run-local.sh stop     停止 —— POST /actuator/shutdown，脚本内没有任何 kill
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/jdk.sh

CONFIG_YML="config/application.yml"
APP_JAR="trader-app/target/trader-app.jar"
STOP_TIMEOUT=40

app_port() {
    # 依赖 server.port 是文件里第一个 port:（datasource 的端口写在 URL 里，不会先出现）
    grep -m1 -E '^[[:space:]]*port:' "$CONFIG_YML" | sed -E 's/[^0-9]//g'
}

start() {
    require_jdk21
    [[ -f "$APP_JAR" ]] || { echo "没有 $APP_JAR，先构建：$(mvn_cmd) -pl trader-app -am -DskipTests package" >&2; exit 1; }
    [[ -f config/secrets.yml ]] || echo "提示：没有 config/secrets.yml（可从 secrets.yml.example 复制）；数据库密码将尝试从 ~/.pgpass 读取"
    echo "启动开发实例（:$(app_port)，JDK $JAVA_HOME）..."
    # -DsocksNonProxyHosts 不能省：本机开着系统级 SOCKS 代理时，pgjdbc 会拿着未解析的回环地址走代理，
    # 报 UnknownHostException: 127.0.0.1——psql/curl 全正常，只有 Java 连不上本机数据库。
    exec "$JAVA_HOME/bin/java" \
        -DsocksNonProxyHosts='localhost|127.*|[::1]' \
        -jar "$APP_JAR" \
        --spring.config.additional-location=file:./config/
}

stop() {
    local port url
    port="$(app_port)"
    [[ -n "$port" ]] || { echo "从 $CONFIG_YML 解析不出端口" >&2; exit 1; }
    url="http://127.0.0.1:$port/actuator/shutdown"
    echo "停止开发实例（:$port）—— 调用 $url ..."
    if ! curl -s -X POST "$url" >/dev/null; then echo "没有实例在监听 :$port"; exit 0; fi
    for ((i = 0; i < STOP_TIMEOUT; i++)); do
        curl -s "http://127.0.0.1:$port/actuator/health" >/dev/null 2>&1 || { echo "已停止"; exit 0; }
        sleep 1
    done
    echo "超过 ${STOP_TIMEOUT}s 端口仍开着，请自行检查" >&2
    exit 1
}

case "${1:-start}" in
    start) start ;;
    stop) stop ;;
    *) echo "用法: $0 [start|stop]" >&2; exit 2 ;;
esac
