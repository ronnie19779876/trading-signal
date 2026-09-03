#!/usr/bin/env bash
#
# 提交前的敏感信息扫描。命中即失败（退出码 1）。
#   ./scripts/check-secrets.sh            扫描全部已跟踪 + 未忽略的文件
#   ./scripts/check-secrets.sh --staged   只扫描暂存区（pre-commit hook 用，见 install-git-hooks.sh）
# 行内加注释 secrets-ok 可豁免误报（例如文档里的示例）。
set -uo pipefail
cd "$(dirname "$0")/.."

if [[ "${1:-}" == "--staged" ]]; then
    files="$(git diff --cached --name-only --diff-filter=ACMR)"
else
    files="$(git ls-files -co --exclude-standard)"
fi
files="$(echo "$files" | grep -vE '(^|/)(node_modules|target|dist)/' | grep -vE '\.(png|jpg|jpeg|gif|ico|woff2?|ttf|jar|zip|gz|lock)$' || true)"
[[ -n "$files" ]] || { echo "没有需要扫描的文件"; exit 0; }

# 模式 | 说明
patterns=(
    '\b(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])(\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}\b|IPv4 地址'
    '\bU[0-9]{7,8}\b|盈透账户号'
    '\bDU[0-9]{6,8}\b|盈透模拟账户号'
    'sk-[A-Za-z0-9_-]{16,}|OpenAI API key'
    '-----BEGIN [A-Z ]*PRIVATE KEY-----|私钥'
    '\b[a-z0-9-]+\.jdkxx\.org\b|私有主机名'
    '(password|passwd|pwd|pwd_md5|api[-_]?key|secret|token)[[:space:]]*[:=][[:space:]]*["'"'"']?[^"'"'"'$#{[:space:]]{6,}|疑似明文口令'
)

hits=0
for entry in "${patterns[@]}"; do
    pattern="${entry%%|*}"; label="${entry##*|}"
    while IFS= read -r line; do
        [[ -z "$line" ]] && continue
        # 豁免：回环地址、注释行、显式标记
        echo "$line" | grep -qE 'secrets-ok' && continue
        echo "$line" | grep -qE '(127\.0\.0\.1|0\.0\.0\.0|255\.255\.255\.[0-9]+)' && [[ "$label" == "IPv4 地址" ]] && continue
        echo "$line" | grep -qE '^[^:]+:[0-9]+:[[:space:]]*(#|//|\*|<!--)' && continue
        echo "  [$label] $line"
        hits=$((hits + 1))
    done < <(echo "$files" | xargs grep -nHE -- "$pattern" 2>/dev/null || true)
done

if (( hits > 0 )); then
    echo "❌ 发现 $hits 处疑似敏感信息，拒绝提交。确认是误报可在该行加注释 secrets-ok"
    exit 1
fi
echo "✅ 敏感信息扫描通过（$(echo "$files" | wc -l | tr -d ' ') 个文件）"
