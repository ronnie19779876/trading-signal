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
# Five more fixed in 3.5.2 (found in the 2026-10-31 review, proven with probe files; the probes now live
# permanently in CheckSecretsScriptTest, which runs this script against them in a temporary repository):
#   7. a quote between keyword and separator (JSON "password": "x") never matched;
#   8. a value had to start with a letter or digit, so !Xyz12345 or {Xy12... slipped through, and #, $
#      or { anywhere inside the value ended the match;
#   9. compact Postman pairs ("key":"db_password","value":"x") and the <env name=.. value=..> form of
#      client-id were not covered at all;
#  10. gateway / SSH port values (TRADER_IBKR_PORT=..., vps_port=...) had no rule;
#  11. editor swap and backup copies of secret files (.trader.env.swp, trader.env~, *.bak) were neither
#      ignored nor rejected; they are now in .gitignore and rejected by name here, so git add -f cannot
#      push one through either.
set -uo pipefail
cd "$(dirname "$0")/.."

staged=false
[[ "${1:-}" == "--staged" ]] && staged=true

# Editor swap files (.name.swp, .name.swo ... .swa-.swp), "name~" and "name.bak" are copies of whatever
# was being edited -- often a secret file whose own ignore rule does not match the copy. Vim swap files
# are binary, so grep -I skips their content: they are rejected by name instead.
backup_re='(^|/)\.[^/]*\.sw[a-p]$|~$|\.bak$'
files=()
backups=()
while IFS= read -r -d '' f; do
    [[ "$f" =~ (^|/)(node_modules|target|dist)/ ]] && continue
    [[ "$f" =~ \.(png|jpg|jpeg|gif|ico|woff2?|ttf|jar|zip|gz|lock)$ ]] && continue
    $staged || [[ -f "$f" ]] || continue    # tracked file already deleted from the working tree
    [[ "$f" =~ $backup_re ]] && backups+=("$f")
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

# Building blocks of the keyword patterns.
#   kw     words that name a secret (matched case-insensitively, see scan)
#   q      either quote character
#   first  the first character of a real value: anything except a quote, whitespace, or a character that
#          starts a placeholder or an expression rather than a literal -- ${VAR}, ${VAR:+..}, {{token}},
#          <your-password>, %s, ****, ..., == / = (comparison, assignment of an expression), +, ?, (..),
#          [..], `..`, -n. A single { followed by something else is a literal value ({Xy12...).
#   rest   the remaining characters: anything up to a quote or whitespace (#, $ and { included)
kw='(password|passwd|pwd|pwd_md5|api[-_]?key|secret|token)'
q="[\"']"
first="([^\"'\$#{<%*.=+?([\`[:space:]-]|\\{[^{\"'[:space:]])"
rest="[^\"'[:space:]]"

# pattern|label (split on the LAST |, so a pattern may contain | itself)
patterns=(
    '\b(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])(\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}\b|IPv4 地址'
    '\bU[0-9]{7,8}\b|盈透账户号'
    '\bDU[0-9]{6,8}\b|盈透模拟账户号'
    'sk-[A-Za-z0-9_-]{16,}|OpenAI API key'
    '-----BEGIN [A-Z ]*PRIVATE KEY-----|私钥'
    '\b[a-z0-9-]+\.jdkxx\.org\b|私有主机名'
    # client-id: <n> / CLIENT_ID=<n> / <env name="TRADER_IBKR_CLIENT_ID" value="<n>" />
    "client[-_ ]?id${q}?[[:space:]]*(value[[:space:]]*=|[:=])[[:space:]]*${q}?[0-9]+|IB client-id 具体值"
    # password: x / TRADER_DB_PASSWORD=x / "password": "x"
    "${kw}${q}?[[:space:]]*[:=][[:space:]]*${q}?${first}${rest}{5,}|疑似明文口令"
    # <env name="TRADER_DB_PASSWORD" value="x" /> (IntelliJ .run/*.run.xml)
    "${kw}[A-Za-z0-9_]*${q}[[:space:]]*(value|content)[[:space:]]*=[[:space:]]*${q}${first}${rest}{5,}|疑似明文口令（属性形式）"
    # Postman: {"key": "db_password", "value": "x"}, with or without spaces
    "\"key\"[[:space:]]*:[[:space:]]*\"[^\"]*${kw}[^\"]*\"[[:space:]]*,[[:space:]]*\"value\"[[:space:]]*:[[:space:]]*\"${first}${rest}{5,}|疑似明文口令（键值对形式）"
    # Gateway / SSH ports identify our setup just like hosts do: TRADER_IBKR_PORT=..., vps_port=...,
    # api_port=... / telnet_port=... (FutuOpenD.xml), <env name="TRADER_FUTU_PORT" value="..." />.
    # The application's own HTTP ports (8083 / 8093) are public and written as server.port, not matched.
    "(ibkr|futu|opend|tws|gateway|ssh|vps|shanghai|api|telnet)[-_. ]?port${q}?[[:space:]]*(value[[:space:]]*=|[:=])[[:space:]]*${q}?[0-9]{2,5}|网关 / SSH 端口具体值"
)

hits=0
if (( ${#backups[@]} > 0 )); then
    for f in "${backups[@]}"; do
        echo "  [编辑器交换 / 备份文件] ${f}"
        hits=$((hits + 1))
    done
fi
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
