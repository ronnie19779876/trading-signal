#!/usr/bin/env bash
# 日内可行性研究（ARCHITECTURE §24）的运行入口：编译 src/*.java 后运行指定主类。
#   research/intraday/run.sh <主类> [参数...]
# 依赖：先构建过 trader-domain（./mvnw -o -pl trader-domain -am -DskipTests compile）；富途 SDK 与 Jackson 取本地 Maven 仓库。
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
source "$root/scripts/lib/jdk.sh"
require_jdk21

repo="$(sed -n 's:.*<localRepository>\(.*\)</localRepository>.*:\1:p' "$HOME/.m2/settings.xml" 2>/dev/null || true)"
repo="${repo:-$HOME/.m2/repository}"
jars=(
  "$root/trader-domain/target/classes"
  "$root/trader-common/target/classes"
  "$repo/com/fasterxml/jackson/core/jackson-databind/2.21.2/jackson-databind-2.21.2.jar"
  "$repo/com/fasterxml/jackson/core/jackson-core/2.21.2/jackson-core-2.21.2.jar"
  "$repo/com/fasterxml/jackson/core/jackson-annotations/2.21/jackson-annotations-2.21.jar"
  "$repo/org/postgresql/postgresql/42.7.10/postgresql-42.7.10.jar"
  "$repo/org/jdkxx/trader/futu-api-shaded/10.10.7008/futu-api-shaded-10.10.7008.jar"
  "$repo/org/bouncycastle/bcprov-jdk15on/1.68/bcprov-jdk15on-1.68.jar"
  "$repo/org/bouncycastle/bcpkix-jdk15on/1.68/bcpkix-jdk15on-1.68.jar"
)
for j in "${jars[@]}"; do
  [[ -e "$j" ]] || { echo "缺少依赖：$j" >&2; exit 1; }
done
cp="$(IFS=:; echo "${jars[*]}")"
mkdir -p "$here/data/classes"
"$JAVA_HOME/bin/javac" -nowarn -encoding UTF-8 -cp "$cp" -d "$here/data/classes" "$here"/src/*.java
cd "$here"
exec "$JAVA_HOME/bin/java" -DsocksNonProxyHosts='localhost|127.*|[::1]' -cp "$here/data/classes:$cp" "$@"
