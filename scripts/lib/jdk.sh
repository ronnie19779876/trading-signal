#!/usr/bin/env bash
# Sourced by other scripts. Purpose: never hard-code the JDK path -- a hard-coded absolute path has already
# broken in another project, and swapping in a different absolute path only postpones the breakage.
# JDK 21 specifically: the Futu SDK's protobuf 3.5.1 uses sun.misc.Unsafe (JDK 24+ warns, 26 throws).
#
#   resolve_jdk21   probes candidates in order and prints the JAVA_HOME of a JDK 21 after **checking the
#                   major version** (a directory name proves nothing; only java -version is authoritative)
#   require_jdk21   exits with an error when none is found; otherwise exports JAVA_HOME
#   mvn_cmd         mvnw when present, otherwise mvn from PATH

resolve_jdk21() {
    local candidates=() c
    [[ -n "${JDK21_HOME:-}" ]] && candidates+=("$JDK21_HOME")
    [[ -n "${JAVA_HOME:-}" ]] && candidates+=("$JAVA_HOME")
    if [[ -x /usr/libexec/java_home ]]; then
        c="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
        [[ -n "$c" ]] && candidates+=("$c")
    fi
    while IFS= read -r c; do
        [[ -n "$c" ]] && candidates+=("$c/Contents/Home")
    done < <(ls -d "$HOME"/Java/jdk-21*.jdk 2>/dev/null | sort -Vr)
    candidates+=(
        /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
        /usr/lib/jvm/temurin-21-jdk-amd64
        /usr/lib/jvm/java-21-openjdk-amd64
    )
    if command -v java >/dev/null 2>&1; then
        c="$(command -v java)"
        c="$(cd "$(dirname "$c")/.." && pwd)"
        candidates+=("$c")
    fi
    for c in "${candidates[@]}"; do
        [[ -x "$c/bin/java" ]] || continue
        [[ "$("$c/bin/java" -version 2>&1 | head -1)" == *'"21.'* ]] || continue
        printf '%s' "$c"
        return 0
    done
    return 1
}

require_jdk21() {
    local home
    home="$(resolve_jdk21)" || {
        echo '未找到 JDK 21。已依次探测：${JDK21_HOME}、${JAVA_HOME}、/usr/libexec/java_home -v 21、~/Java/jdk-21*.jdk、常见 Linux 路径、PATH 上的 java' >&2
        echo '  指定位置：JDK21_HOME=/路径/到/JDK21 <脚本>' >&2
        exit 1
    }
    export JAVA_HOME="$home"
}

mvn_cmd() {
    if [[ -x ./mvnw ]]; then echo ./mvnw; else echo mvn; fi
}
