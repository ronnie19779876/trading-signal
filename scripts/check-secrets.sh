#!/usr/bin/env bash
#
# 提交前的敏感信息扫描。命中即失败（退出码 1）。
#   ./scripts/check-secrets.sh            扫描全部已跟踪 + 未忽略的文件（工作区内容）
#   ./scripts/check-secrets.sh --staged   扫描暂存区里将要提交的内容（pre-commit hook 用，见 install-git-hooks.sh）
# 误报在该行加 secrets-ok 豁免（例如文档里的示例）。注释行不豁免：注释里写的口令一样会被提交。
#
# 2.0.2 前的四处缺陷（都会让扫描静默漏报）：
#   1. 用 ${entry%%|*} 切"模式|说明"，切在第一个 | 上：IPv4 与明文口令两个模式本身带 | 分支，被切成非法正则，
#      grep 报错又被 2>/dev/null 吞掉——这两类从来没扫到过；
#   2. 文件清单经 xargs 按空白切分，带空格的文件名（.run/TraderApplication (local).run.xml）从来没扫到；
#   3. --staged 扫的是工作区文件，不是暂存区里真正要提交的内容；
#   4. 注释行整行豁免，`# password: xxx` 能混过去。
set -uo pipefail
cd "$(dirname "$0")/.."

staged=false
[[ "${1:-}" == "--staged" ]] && staged=true

files=()
while IFS= read -r -d '' f; do
    [[ "$f" =~ (^|/)(node_modules|target|dist)/ ]] && continue
    [[ "$f" =~ \.(png|jpg|jpeg|gif|ico|woff2?|ttf|jar|zip|gz|lock)$ ]] && continue
    $staged || [[ -f "$f" ]] || continue    # 工作区里已删除的跟踪文件
    files+=("$f")
done < <(if $staged; then git diff --cached --name-only -z --diff-filter=ACMR; else git ls-files -z -co --exclude-standard; fi)
(( ${#files[@]} > 0 )) || { echo "没有需要扫描的文件"; exit 0; }

# 输出"路径:行号:内容"。用系统 grep -E（git grep 的正则引擎不认 \b）
scan() {
    local pattern="$1" f
    if $staged; then
        for f in "${files[@]}"; do
            git show ":$f" 2>/dev/null | grep -nIE -- "$pattern" | awk -v p="$f" '{ print p ":" $0 }'
        done
    else
        printf '%s\0' "${files[@]}" | xargs -0 grep -nHIE -- "$pattern"
    fi
}

# 模式|说明（按最后一个 | 切分：模式里可以有 |）
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
    pattern="${entry%|*}"; label="${entry##*|}"
    # 模式本身写坏时要报出来，不能当成"没命中"
    printf '' | grep -E -- "$pattern" >/dev/null 2>&1
    if [[ $? -eq 2 ]]; then
        echo "❌ 扫描模式非法（$label）：$pattern" >&2
        exit 2
    fi
    while IFS= read -r line; do
        [[ -z "$line" ]] && continue
        # 豁免：显式标记；IPv4 里的回环与广播地址
        [[ "$line" == *secrets-ok* ]] && continue
        if [[ "$label" == "IPv4 地址" ]] && echo "$line" | grep -qE '(127\.0\.0\.1|0\.0\.0\.0|255\.255\.255\.[0-9]+)'; then
            continue
        fi
        echo "  [$label] $line"
        hits=$((hits + 1))
    done < <(scan "$pattern")
done

if (( hits > 0 )); then
    echo "❌ 发现 $hits 处疑似敏感信息，拒绝提交。确认是误报可在该行加注释 secrets-ok"
    exit 1
fi
echo "✅ 敏感信息扫描通过（${#files[@]} 个文件）"
