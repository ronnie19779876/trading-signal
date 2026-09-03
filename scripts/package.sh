#!/usr/bin/env bash
#
# 打发布包：前端 build → 打进 jar → dist/trading-signal-<版本>-<时间戳>.tar.gz
# 解压后的布局：bin/trader.sh  config/application.yml  config/trader.env.example  lib/trader-app.jar  systemd/  logs/
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/jdk.sh
require_jdk21
MVN="$(mvn_cmd)"

VERSION="$(grep -oE '<revision>[^<]+' pom.xml | sed 's/.*>//')"
STAMP="$(date +%Y%m%d-%H%M)"
NAME="trading-signal-$VERSION"
OUT="dist/$NAME"

if command -v npm >/dev/null 2>&1; then
    echo "构建前端 ..."
    (cd trader-web && npm ci --no-audit --no-fund --silent && npm run build --silent)
else
    echo "⚠️ 没有 npm，跳过前端构建；jar 里将只有已存在的 trader-app/src/main/resources/static 内容（若有）"
fi

echo "构建后端（跳过测试；测试请单独跑 $MVN verify）..."
"$MVN" -q clean package -DskipTests -pl trader-app -am

rm -rf "$OUT"; mkdir -p "$OUT"/{bin,config,lib,logs,systemd}
cp deploy/bin/trader.sh "$OUT/bin/"
cp deploy/config/application.yml "$OUT/config/"
cp deploy/config/trader.env.example "$OUT/config/"
cp deploy/systemd/trading-signal.service "$OUT/systemd/"
cp trader-app/target/trader-app.jar "$OUT/lib/"
mkdir -p dist
tar -C dist -czf "dist/$NAME-$STAMP.tar.gz" "$NAME"
rm -rf "$OUT"
echo "产物：dist/$NAME-$STAMP.tar.gz"
echo "部署提示：升级 = 只替换 lib/ 与 bin/，保留服务器上的 config/trader.env；首装才从 example 复制并填值"
