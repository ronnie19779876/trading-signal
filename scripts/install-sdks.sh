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
# The downloaded jar is cached per version (sdk/tws-api/TwsApi-<version>.jar). Until 3.5.2 the cache was a
# single sdk/tws-api/TwsApi.jar that was reused whatever version it held, and an IBJts/ tree left by an
# older zip could supply the jar that find picked up, so a version bump could install the old SDK under
# the new version number (found in the 2026-10-31 review).
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
    CACHED="sdk/tws-api/TwsApi-${TWS_VER}.jar"
    JAR="${TWS_API_JAR:-${CACHED}}"
    if [[ ! -f "$JAR" ]]; then
        # Official file name: twsapi_macunix.1030.01.zip (10.30.01 -> 1030.01)
        MM="${TWS_VER%.*}"; PATCH="${TWS_VER##*.}"
        ZIP_NAME="twsapi_macunix.${MM//./}.${PATCH}.zip"
        URL="https://interactivebrokers.github.io/downloads/$ZIP_NAME"
        echo "下载 $URL ..."
        mkdir -p sdk/tws-api
        curl -fL --retry 3 -o "sdk/tws-api/$ZIP_NAME" "$URL" || {
            echo "下载失败。请从 https://interactivebrokers.github.io/ 手工下载并把 TwsApi.jar 放到 ${CACHED}" >&2
            exit 1
        }
        # A tree left by an older zip must not supply the jar or the version file
        rm -rf sdk/tws-api/IBJts
        (cd sdk/tws-api && unzip -qo "$ZIP_NAME" 'IBJts/source/JavaClient/TwsApi.jar' 'IBJts/API_VersionNum.txt' 2>/dev/null || unzip -qo "$ZIP_NAME")
        FOUND=sdk/tws-api/IBJts/source/JavaClient/TwsApi.jar
        [[ -f "$FOUND" ]] || { echo "zip 里没找到 IBJts/source/JavaClient/TwsApi.jar" >&2; exit 1; }
        VERSION_FILE=sdk/tws-api/IBJts/API_VersionNum.txt
        if [[ -f "${VERSION_FILE}" ]] && grep -qF -- "${TWS_VER}" "${VERSION_FILE}"; then
            echo "zip 内版本：$(cat "${VERSION_FILE}")"
        else
            echo "⚠️ zip 内版本文件没有写 ${TWS_VER}（$(cat "${VERSION_FILE}" 2>/dev/null || echo 没有版本文件)），确认下载的是不是这个版本" >&2
        fi
        cp "$FOUND" "${CACHED}"
        JAR="${CACHED}"
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
