#!/usr/bin/env bash
# 安装 pre-commit hook：提交前跑 scripts/check-secrets.sh --staged。每台开发机克隆后执行一次。
set -euo pipefail
cd "$(dirname "$0")/.."
hook=.git/hooks/pre-commit
cat > "$hook" <<'HOOK'
#!/usr/bin/env bash
exec "$(git rev-parse --show-toplevel)/scripts/check-secrets.sh" --staged
HOOK
chmod +x "$hook"
echo "已安装 $hook"
