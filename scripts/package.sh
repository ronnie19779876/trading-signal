#!/usr/bin/env bash
#
# 打发布包：前端 build → 打进 jar → dist/trading-signal-<版本>-<时间戳>.tar.gz
# 解压后的布局：bin/trader.sh  config/application.yml  config/trader.env.example  lib/trader-app.jar  systemd/  logs/
#   ./scripts/package.sh                   只接受正式版（部署到生产的必须是正式版）
#   ./scripts/package.sh --allow-snapshot  本机试打包
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/jdk.sh
require_jdk21
MVN="$(mvn_cmd)"

VERSION="$(grep -oE '<revision>[^<]+' pom.xml | sed 's/.*>//')"
if [[ "$VERSION" == *-SNAPSHOT && "${1:-}" != "--allow-snapshot" ]]; then
    echo "❌ 版本是 $VERSION：部署到生产的必须是正式版（发布流程见 README「版本规则」）；本机试打包加 --allow-snapshot" >&2
    exit 1
fi
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
cat <<'TIP'
部署提示（与 docs/OPERATIONS.md §3 一致；别只替换 lib/ 与 bin/）：
  升级 = 解压到新的时间戳目录 → 把旧目录的 config/trader.env 拷过去 → 停旧 → 改软链 → 启新。
  config/application.yml 随版本更新（新 cron 等都在里面），只换 lib/ 会让它永远停在旧版本，
  表现是「本地全绿、生产启动即失败」或新作业根本不跑。
  首装才从 config/trader.env.example 复制并填值。
TIP
