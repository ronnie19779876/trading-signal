#!/usr/bin/env bash
#
# Installs two artifacts that are not on Maven Central into the local repository (run once after cloning;
# re-run after a version upgrade):
#   1. com.interactivebrokers:tws-api        IBKR TWS API (version from tws-api.version in the root pom);
#                                            its license does not allow redistribution, so the jar is never committed
#   2. org.jdkxx.trader:futu-api-shaded      the Futu SDK with protobuf relocated (version from
#                                            futu-api-shaded.version in the root pom)
#
# Usage: ./scripts/install-sdks.sh            skips what is already installed
#        FORCE=1 ./scripts/install-sdks.sh    forces a reinstall
#        TWS_API_JAR=/path/TwsApi.jar ./scripts/install-sdks.sh   offline: use an already downloaded jar
# Note: a cached sdk/tws-api/TwsApi.jar from an earlier run is reused as-is, whatever version it is.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/jdk.sh
require_jdk21
MVN="$(mvn_cmd)"

TWS_VER="$(grep -oE '<tws-api.version>[^<]+' pom.xml | sed 's/.*>//')"
FUTU_VER="$(grep -oE '<futu-api-shaded.version>[^<]+' pom.xml | sed 's/.*>//')"
LOCAL_REPO="$("$MVN" -q help:evaluate -Dexpression=settings.localRepository -DforceStdout)"
echo "本地仓库：$LOCAL_REPO"

# ---------- 1. tws-api ----------
TWS_IN_REPO="$LOCAL_REPO/com/interactivebrokers/tws-api/$TWS_VER/tws-api-$TWS_VER.jar"
if [[ -f "$TWS_IN_REPO" && -z "${FORCE:-}" ]]; then
    echo "tws-api $TWS_VER 已在本地仓库，跳过"
else
    JAR="${TWS_API_JAR:-sdk/tws-api/TwsApi.jar}"
    if [[ ! -f "$JAR" ]]; then
        # Official file name: twsapi_macunix.1030.01.zip (10.30.01 -> 1030.01)
        MM="${TWS_VER%.*}"; PATCH="${TWS_VER##*.}"
        ZIP_NAME="twsapi_macunix.${MM//./}.${PATCH}.zip"
        URL="https://interactivebrokers.github.io/downloads/$ZIP_NAME"
        echo "下载 $URL ..."
        mkdir -p sdk/tws-api
        curl -fL --retry 3 -o "sdk/tws-api/$ZIP_NAME" "$URL" || {
            echo "下载失败。请从 https://interactivebrokers.github.io/ 手工下载并把 TwsApi.jar 放到 sdk/tws-api/TwsApi.jar" >&2
            exit 1
        }
        (cd sdk/tws-api && unzip -qo "$ZIP_NAME" 'IBJts/source/JavaClient/TwsApi.jar' 'IBJts/API_VersionNum.txt' 2>/dev/null || unzip -qo "$ZIP_NAME")
        FOUND="$(find sdk/tws-api -name 'TwsApi.jar' | head -1)"
        [[ -n "$FOUND" ]] || { echo "zip 里没找到 TwsApi.jar" >&2; exit 1; }
        cp "$FOUND" sdk/tws-api/TwsApi.jar
        JAR=sdk/tws-api/TwsApi.jar
        [[ -f sdk/tws-api/IBJts/API_VersionNum.txt ]] && echo "zip 内版本：$(cat sdk/tws-api/IBJts/API_VersionNum.txt)"
    fi
    echo "安装 tws-api $TWS_VER ← $JAR"
    "$MVN" -q install:install-file -Dfile="$JAR" -DgroupId=com.interactivebrokers -DartifactId=tws-api \
        -Dversion="$TWS_VER" -Dpackaging=jar
fi

# ---------- 2. futu-api-shaded ----------
FUTU_IN_REPO="$LOCAL_REPO/org/jdkxx/trader/futu-api-shaded/$FUTU_VER/futu-api-shaded-$FUTU_VER.jar"
if [[ -f "$FUTU_IN_REPO" && -z "${FORCE:-}" ]]; then
    echo "futu-api-shaded $FUTU_VER 已在本地仓库，跳过"
else
    echo "构建并安装 futu-api-shaded $FUTU_VER ..."
    "$MVN" -q -f sdk/futu-api-shaded/pom.xml clean install
fi
echo "SDK 就绪"
