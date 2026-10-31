#!/usr/bin/env bash
#
# Installs the node-side parts of the primary/standby setup on one server (idempotent, safe to re-run;
# needs no sudo):
#   ~/pg-replica/bin/{watchdog.sh,backup.sh}, the systemd user units (tunnel, watchdog timer),
#   ~/.config/pg-replica/peer.env (peer = the other server), the peer's host key (checked first against the
#   fingerprint already trusted on this machine), and the crontab entry for the daily backup.
# Depending on the node's current role: the tunnel is enabled on a standby and disabled on a primary.
#   ./scripts/replica/install-node.sh vps|shanghai
#
# Prerequisites (one-off, need sudo, see OPERATIONS "primary/standby switchover" section): both sides
# already have ~/.ssh/id_pg_replica with the public key added, with restrictions, to the other side's
# authorized_keys; loginctl enable-linger; /etc/postgresql/18/main/conf.d/replica.conf; postgres's ~/.pgpass.
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/replica/lib.sh
load_env

NODE="${1:-}"
valid_node "${NODE}" || { echo "用法: $0 vps|shanghai" >&2; exit 2; }
PEER="$(other "${NODE}")"
PEER_TARGET="$(ssh_target "${PEER}")"
PEER_USER="${PEER_TARGET%@*}"
PEER_HOST="${PEER_TARGET#*@}"
PEER_PORT="$(ssh_port "${PEER}")"

# Peer host key: what ssh-keyscan sees from the node must match the key already trusted in this machine's
# known_hosts, otherwise nothing is written (protection against a man in the middle).
# Note: the key appended to the node's known_hosts further down comes from a second ssh-keyscan, not from
# the scan verified here, and an existing entry on the node is left as it is.
if [[ "${PEER_PORT}" == 22 ]]; then KH="${PEER_HOST}"; else KH="[${PEER_HOST}]:${PEER_PORT}"; fi
TRUSTED="$(ssh-keygen -F "${KH}" 2>/dev/null | grep -v '^#' | ssh-keygen -lf - 2>/dev/null | awk '$NF=="(ED25519)"{print $2}')"
[[ -n "${TRUSTED}" ]] || die "本机 known_hosts 里没有 ${PEER} 的 ED25519 主机密钥，先从本机 ssh 登录一次 ${PEER}"
SEEN="$(on "${NODE}" "ssh-keyscan -T 10 -p ${PEER_PORT} -t ed25519 ${PEER_HOST} 2>/dev/null" | ssh-keygen -lf - 2>/dev/null | awk '{print $2}')"
[[ "${SEEN}" == "${TRUSTED}" ]] || die "${NODE} 看到的 ${PEER} 主机指纹（${SEEN:-无}）与本机信任的不一致，停止"
say "${NODE} → ${PEER} 主机指纹一致"

on "${NODE}" "mkdir -p ~/pg-replica/bin ~/.config/systemd/user ~/.config/pg-replica"
for f in watchdog.sh backup.sh; do
  on_in "${NODE}" "cat > ~/pg-replica/bin/${f} && chmod 755 ~/pg-replica/bin/${f}" < "scripts/replica/node/${f}"
done
for f in pg-replica-tunnel.service pg-replica-watchdog.service pg-replica-watchdog.timer; do
  on_in "${NODE}" "cat > ~/.config/systemd/user/${f}" < "scripts/replica/node/${f}"
done
on_in "${NODE}" "umask 077; cat > ~/.config/pg-replica/peer.env" <<EOF
# 对端（${PEER}）；install-node.sh 生成，只在本机，不进仓库
PEER_USER=${PEER_USER}
PEER_HOST=${PEER_HOST}
PEER_PORT=${PEER_PORT}
EOF
on "${NODE}" "ssh-keygen -F '${KH}' >/dev/null || ssh-keyscan -T 10 -p ${PEER_PORT} -t ed25519 ${PEER_HOST} 2>/dev/null >> ~/.ssh/known_hosts"
on "${NODE}" '(crontab -l 2>/dev/null | grep -v "# pg-replica-backup$"; echo "0 12 * * * \${HOME}/pg-replica/bin/backup.sh # pg-replica-backup") | crontab -'
on "${NODE}" "systemctl --user daemon-reload && systemctl --user enable --now pg-replica-watchdog.timer >/dev/null 2>&1"
say "${NODE} 已装：脚本、单元、peer.env、主机密钥、备份 crontab（每天 12:00）、看门狗定时器"

ROLE="$(sql "${NODE}" "select pg_is_in_recovery()" 2>/dev/null || true)"
case "${ROLE}" in
  t) on "${NODE}" "systemctl --user enable --now ${TUNNEL_UNIT} >/dev/null 2>&1"; say "${NODE} 是备库：隧道已开" ;;
  f) on "${NODE}" "systemctl --user disable --now ${TUNNEL_UNIT} >/dev/null 2>&1 || true"; say "${NODE} 是主库：隧道已关" ;;
  *) say "${NODE} 数据库连不上：隧道保持现状（重建备库期间正常）" ;;
esac
