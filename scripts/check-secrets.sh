#!/usr/bin/env bash
#
# Pre-commit scan for sensitive information. Any hit fails the run (exit code 1).
#   ./scripts/check-secrets.sh            scan every tracked + non-ignored file (working-tree content)
#   ./scripts/check-secrets.sh --staged   scan the staged content that is about to be committed
#                                         (used by the pre-commit hook, see install-git-hooks.sh)
# To exempt a false positive, put secrets-ok on that line (e.g. an example in the docs). Comment lines
# are NOT exempt: a password written in a comment gets committed just the same.
#
# Four defects before 2.0.2 (each made the scan miss hits silently):
#   1. "pattern|label" was split with ${entry%%|*}, i.e. on the FIRST |. The IPv4 and plaintext-password
#      patterns contain | alternations themselves, so they were cut into invalid regexes, and the grep
#      error was swallowed by 2>/dev/null -- those two categories were never scanned at all;
#   2. the file list went through xargs, which splits on whitespace, so file names containing spaces
#      (.run/TraderApplication (local).run.xml) were never scanned;
#   3. --staged scanned the working-tree files, not the staged content that is actually committed;
#   4. comment lines were exempt as a whole, so `# password: xxx` slipped through.
# Two more fixed in 3.1.2 (found in the 2026-09-25 full-project review, each proven with probe files):
#   5. the keyword patterns were all lowercase while grep ran without -i: TRADER_DB_PASSWORD /
#      OPENAI_API_KEY / TRADER_ACCOUNT_KEY_SECRET, which this project really uses, and the
#      <env name=.. value=..> form in .run/*.run.xml were all missed silently;
#   6. IPv4 hits were exempted per line ("the line contains loopback, skip the whole line"), but a tunnel
#      command line has the loopback address and the real host address on the same line, so the real
#      public address was let through along with it -- now every address is judged on its own.
set -uo pipefail
cd "$(dirname "$0")/.."

staged=false
[[ "${1:-}" == "--staged" ]] && staged=true

files=()
while IFS= read -r -d '' f; do
    [[ "$f" =~ (^|/)(node_modules|target|dist)/ ]] && continue
    [[ "$f" =~ \.(png|jpg|jpeg|gif|ico|woff2?|ttf|jar|zip|gz|lock)$ ]] && continue
    $staged || [[ -f "$f" ]] || continue    # tracked file already deleted from the working tree
    files+=("$f")
done < <(if $staged; then git diff --cached --name-only -z --diff-filter=ACMR; else git ls-files -z -co --exclude-standard; fi)
(( ${#files[@]} > 0 )) || { echo "没有需要扫描的文件"; exit 0; }

# Prints "path:line:content". Uses the system grep -E (git grep's regex engine does not understand \b).
# -i is mandatory: this project's passwords/keys appear as uppercase environment variables
# (TRADER_DB_PASSWORD, OPENAI_API_KEY, TRADER_ACCOUNT_KEY_SECRET); lowercase patterns without -i miss
# all of them silently (measured with probe files on 2026-09-25).
# In --staged mode each file is read from the index (git show :path), so what is scanned is exactly what
# will be committed, not whatever happens to be in the working tree.
scan() {
    local pattern="$1" f
    if $staged; then
        for f in "${files[@]}"; do
            git show ":$f" 2>/dev/null | grep -niIE -- "$pattern" | awk -v p="$f" '{ print p ":" $0 }'
        done
    else
        printf '%s\0' "${files[@]}" | xargs -0 grep -niHIE -- "$pattern"
    fi
}

# pattern|label (split on the LAST |, so a pattern may contain | itself)
patterns=(
    '\b(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])(\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}\b|IPv4 地址'
    '\bU[0-9]{7,8}\b|盈透账户号'
    '\bDU[0-9]{6,8}\b|盈透模拟账户号'
    'sk-[A-Za-z0-9_-]{16,}|OpenAI API key'
    '-----BEGIN [A-Z ]*PRIVATE KEY-----|私钥'
    '\b[a-z0-9-]+\.jdkxx\.org\b|私有主机名'
    'client[-_ ]?id["'"'"']?[[:space:]]*[:=][[:space:]]*["'"'"']?[0-9]+|IB client-id 具体值'
    '(password|passwd|pwd|pwd_md5|api[-_]?key|secret|token)[[:space:]]*[:=][[:space:]]*["'"'"']?[A-Za-z0-9][^"'"'"'$#{[:space:]]{5,}|疑似明文口令'
    '(password|passwd|pwd|api[-_]?key|secret|token)[A-Za-z0-9_]*["'"'"'][[:space:]]*(value|content)[[:space:]]*=[[:space:]]*["'"'"'][A-Za-z0-9][^"'"'"'$#{]{5,}|疑似明文口令（属性形式）'
)

hits=0
for entry in "${patterns[@]}"; do
    pattern="${entry%|*}"; label="${entry##*|}"
    # A broken pattern must be reported, not treated as "no hits" (grep exits 2 on an invalid regex)
    printf '' | grep -E -- "$pattern" >/dev/null 2>&1
    if [[ $? -eq 2 ]]; then
        echo "❌ 扫描模式非法（${label}）：$pattern" >&2
        exit 2
    fi
    while IFS= read -r line; do
        [[ -z "$line" ]] && continue
        # Exemptions: the explicit marker; loopback and broadcast addresses for IPv4
        [[ "$line" == *secrets-ok* ]] && continue
        # IPv4 is judged address by address, never per line: a tunnel command line holds the loopback and
        # the real host address together, and a per-line exemption lets the real public address through as
        # well (probe on 2026-09-25: ssh -L 5432:127.0.0.1:5432 user@<public IP> passed silently)
        if [[ "$label" == "IPv4 地址" ]]; then
            content="${line#*:}"; content="${content#*:}"     # strip the path:line: prefix
            unexempt=0
            while IFS= read -r ip; do
                [[ -z "$ip" ]] && continue
                [[ "$ip" == 127.* || "$ip" == "0.0.0.0" || "$ip" == 255.255.255.* ]] && continue
                unexempt=1
            done < <(printf '%s\n' "$content" | grep -oE '\b(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])(\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}\b')
            (( unexempt )) || continue
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
