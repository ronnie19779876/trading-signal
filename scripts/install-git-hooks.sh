#!/usr/bin/env bash
# Installs the pre-commit hook, which runs scripts/check-secrets.sh --staged before every commit.
# Run once on every development machine after cloning. Overwrites any existing pre-commit hook.
set -euo pipefail
cd "$(dirname "$0")/.."
hook=.git/hooks/pre-commit
cat > "$hook" <<'HOOK'
#!/usr/bin/env bash
exec "$(git rev-parse --show-toplevel)/scripts/check-secrets.sh" --staged
HOOK
chmod +x "$hook"
echo "已安装 $hook"
