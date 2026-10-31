#!/usr/bin/env bash
#
# Planned primary/standby switchover: moves the system from the node that is currently primary to <target>
# (ARCHITECTURE §27, OPERATIONS "primary/standby switchover" section).
#   ./scripts/replica/switchover.sh vps|shanghai
# Needs a human at the terminal: sudo prompts for a password on each server.
#
# Sequence (rehearsed round-trip with two temporary clusters on the Shanghai server on 2026-10-19):
#   checks (both reachable, roles right, same version, standby streaming) -> stop the application on the old
#   side -> CHECKPOINT on the old side and take its position -> wait for the target's replay to catch up
#   -> clean shutdown of PG on the old side -> verify that the position received by the target is past the
#   old side's shutdown checkpoint (if not, stop here; the old side can be rolled back untouched)
#   -> promote the target, create the replication slot, close the target's tunnel -> on the old side open
#   the tunnel towards the target, start as a standby, drop the slot left over on it
#   -> verify the old side is streaming -> start the application on the target.
# A planned switchover needs no pg_rewind: on a clean shutdown the old primary ships its last WAL
# (including the shutdown checkpoint), and the new timeline forks exactly there.
#
# Not done here (a reminder is printed; do it by hand): the gateways (IBKR allows one session per user
# name; several Futu OpenD instances on one account kick each other's quotes) and re-pointing the local
# tunnels.
# Every destructive step happens only after the operator types the target name; each die message states
# what has and has not been touched yet and how to roll back.
set -uo pipefail
cd "$(dirname "$0")/../.."
source scripts/replica/lib.sh
load_env

TARGET="${1:-}"
valid_node "${TARGET}" || { echo "用法: $0 vps|shanghai" >&2; exit 2; }
OLD="$(other "${TARGET}")"

# ------------------------------------------------------------------ checks (read-only)
say "检查：${OLD}（现主库）→ ${TARGET}（目标）"
on "${OLD}" true || die "${OLD} 连不上。主库所在机器已不可用时不能走计划内切换，见 OPERATIONS「主库宕机」"
on "${TARGET}" true || die "${TARGET} 连不上"
[[ "$(sql "${OLD}" "select pg_is_in_recovery()")" == f ]] || die "${OLD} 不是主库"
[[ "$(sql "${TARGET}" "select pg_is_in_recovery()")" == t ]] || die "${TARGET} 不是备库"
d_old="$(app_dir "${OLD}")"; d_new="$(app_dir "${TARGET}")"
[[ -n "${d_new}" ]] || die "${TARGET} 上没有 ~/trading-signal（先把当前版本部署过去）"
[[ "${d_old%-*-*}" == "${d_new%-*-*}" ]] || die "两边应用版本不同：${OLD}=${d_old}，${TARGET}=${d_new}（先把同一版本部署到 ${TARGET}）"
app_running "${TARGET}" && die "${TARGET} 上已经有应用在跑"
st="$(sql "${TARGET}" "select status from pg_stat_wal_receiver")"
[[ "${st}" == streaming ]] || die "${TARGET} 的 WAL 接收状态是「${st:-没有}」，不是 streaming"
lag="$(sql "${OLD}" "select pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn)::bigint from pg_stat_replication where application_name = '${TARGET}'")"
[[ -n "${lag}" ]] || die "${OLD} 上看不到备库 ${TARGET}"
say "检查通过：版本 ${d_new%-*-*}，${TARGET} streaming，回放落后 ${lag} 字节"
echo
echo "将要：停 ${OLD} 的应用与数据库 → 提升 ${TARGET} 为主库 → ${OLD} 改为备库 → 在 ${TARGET} 启动应用。"
ask "确认请输入目标名 ${TARGET}："
[[ "${ANSWER}" == "${TARGET}" ]] || die "未确认（收到「${ANSWER}」，应为「${TARGET}」），什么都没做"

# ------------------------------------------------------------------ old side: stop the application, take the position
# The CHECKPOINT/LSN result is written to a file on the node because on_tty output cannot be captured
# (it carries the sudo prompt); the redirect runs as the SSH user, not as postgres.
say "停 ${OLD} 的应用"
on "${OLD}" '~/trading-signal/bin/trader.sh stop' || true
for i in $(seq 1 30); do app_running "${OLD}" || break; sleep 2; done
app_running "${OLD}" && die "${OLD} 的应用没停下来（看 ~/trading-signal/logs），没有动数据库"

say "${OLD}：CHECKPOINT 并取当前位置（输入 ${OLD} 的 sudo 口令）"
on_tty "${OLD}" "sudo -u postgres psql -AtX -c 'CHECKPOINT' -c 'select pg_current_wal_lsn()' > /tmp/pg-switch.lsn" \
  || die "${OLD} 上 CHECKPOINT 失败；应用已停，数据库未动。恢复运行：在 ${OLD} 执行 ~/trading-signal/bin/trader.sh start"
LSN="$(on "${OLD}" 'tail -1 /tmp/pg-switch.lsn')"
[[ "${LSN}" =~ ^[0-9A-F]+/[0-9A-F]+$ ]] || die "取到的位置不对：${LSN}"

for i in $(seq 1 60); do
  [[ "$(sql "${TARGET}" "select pg_last_wal_replay_lsn() >= '${LSN}'::pg_lsn")" == t ]] && break
  sleep 2
done
[[ "$(sql "${TARGET}" "select pg_last_wal_replay_lsn() >= '${LSN}'::pg_lsn")" == t ]] \
  || die "${TARGET} 两分钟内没追到 ${LSN}；应用已停、数据库未动。恢复运行：在 ${OLD} 执行 ~/trading-signal/bin/trader.sh start"
say "${TARGET} 已回放到 ${LSN}"

# ------------------------------------------------------------------ old side: clean PG shutdown, verify the shutdown checkpoint reached the target
# pg_controldata after the stop gives the cluster state ("shut down" = clean) and the location of the
# shutdown checkpoint; the target must have received WAL beyond that location before it may be promoted,
# otherwise the two timelines would diverge and the old side could no longer follow as a standby.
say "${OLD}：正常关闭数据库（输入 sudo 口令）"
on_tty "${OLD}" "sudo bash -c 'systemctl stop ${PG_UNIT} && /usr/lib/postgresql/18/bin/pg_controldata ${PG_DATA}' > /tmp/pg-switch.ctl" \
  || die "${OLD} 关闭数据库失败；在 ${OLD} 查看 systemctl status ${PG_UNIT}"
state="$(on "${OLD}" "awk -F': *' '/Database cluster state/{print \$2}' /tmp/pg-switch.ctl")"
CKPT="$(on "${OLD}" "awk -F': *' '/Latest checkpoint location/{print \$2}' /tmp/pg-switch.ctl")"
[[ "${state}" == "shut down" ]] || die "${OLD} 的集群状态是「${state}」，不是正常关闭"
say "${OLD} 已正常关闭，关闭检查点 ${CKPT}"
recv="$(sql "${TARGET}" "select pg_last_wal_receive_lsn() > '${CKPT}'::pg_lsn")"
if [[ "${recv}" != t ]]; then
  die "${TARGET} 没收到 ${OLD} 的关闭检查点（收到 $(sql "${TARGET}" "select pg_last_wal_receive_lsn()")），停止。
    ${OLD} 的数据完整、可以原样回滚：在 ${OLD} 执行 sudo systemctl start ${PG_UNIT} 与 ~/trading-signal/bin/trader.sh start"
fi

# ------------------------------------------------------------------ target: promote, create the slot, close the tunnel
# pg_promote(true, 60) returns false (not an error) on timeout, so the recovery state is checked again
# right after.
say "${TARGET}：提升为主库并建复制槽（输入 ${TARGET} 的 sudo 口令）"
on_tty "${TARGET}" "sudo -u postgres psql -AtX -v ON_ERROR_STOP=1 -c 'select pg_promote(true, 60)' -c \"select pg_drop_replication_slot(slot_name) from pg_replication_slots where slot_name = 'replica'\" -c \"select pg_create_physical_replication_slot('replica', true)\" >/dev/null" \
  || die "${TARGET} 提升失败——此时两边数据库都没在当主库，先在 ${TARGET} 查看日志"
[[ "$(sql "${TARGET}" "select pg_is_in_recovery()")" == f ]] || die "${TARGET} 提升后仍在恢复状态"
on "${TARGET}" "systemctl --user disable --now ${TUNNEL_UNIT} >/dev/null 2>&1 || true"
say "${TARGET} 已是主库"

# ------------------------------------------------------------------ old side: turn it into a standby
# From here on the target is already the primary, so failures only warn and the switchover continues;
# a standby that does not attach can be rebuilt later with reseed.sh.
say "${OLD}：隧道指向 ${TARGET}，以备库身份启动（输入 sudo 口令）"
on "${OLD}" "systemctl --user enable --now ${TUNNEL_UNIT} >/dev/null 2>&1" || die "${OLD} 隧道起不来；先跑 scripts/replica/install-node.sh ${OLD}"
for i in $(seq 1 15); do on "${OLD}" 'pg_isready -q -h 127.0.0.1 -p 15432' && break; sleep 2; done
on "${OLD}" 'pg_isready -q -h 127.0.0.1 -p 15432' || die "${OLD} 经隧道连不上 ${TARGET} 的数据库（${TARGET} 已是主库、应用未启动）"
on_tty "${OLD}" "sudo bash -c 'install -o postgres -g postgres -m 600 /dev/null ${PG_DATA}/standby.signal && systemctl start ${PG_UNIT}' && sleep 3 && sudo -u postgres psql -AtX -c \"select pg_drop_replication_slot(slot_name) from pg_replication_slots where slot_name = 'replica'\" >/dev/null" \
  || say "⚠️ ${OLD} 改备库这一步有报错，切换继续，稍后用 status.sh 核对"
for i in $(seq 1 30); do
  [[ "$(sql "${TARGET}" "select count(*) from pg_stat_replication where application_name = '${OLD}' and state = 'streaming'")" == 1 ]] && break
  sleep 2
done
if [[ "$(sql "${TARGET}" "select count(*) from pg_stat_replication where application_name = '${OLD}' and state = 'streaming'")" == 1 ]]; then
  say "${OLD} 已作为备库接上 ${TARGET}（streaming）"
else
  say "⚠️ ${OLD} 没有接上；切换继续，之后用 scripts/replica/reseed.sh ${OLD} 重建备库"
fi

# ------------------------------------------------------------------ target: start the application
say "${TARGET}：启动应用"
on "${TARGET}" '~/trading-signal/bin/trader.sh start' || die "${TARGET} 应用启动失败，看 ~/trading-signal/logs"
health="$(on "${TARGET}" "curl -s -m 10 http://127.0.0.1:8093/actuator/health" 2>/dev/null)"
say "${TARGET} 健康：${health:0:200}"
on "${OLD}" 'rm -f /tmp/pg-switch.lsn /tmp/pg-switch.ctl' || true

cat <<EOF

✅ 数据库已切到 ${TARGET}，应用已在 ${TARGET} 启动。还要人工做：
  1. 网关：在 ${OLD} 退出 IB Gateway、停 OpenD（盈透同一用户名只能一个会话，富途会互踢行情）；在 ${TARGET} 登录 IB Gateway、启 OpenD。
  2. 本机隧道改指 ${TARGET}（8093 / 5432 / 4001 / 11111）。
  3. ./scripts/replica/status.sh 确认；./scripts/check-daily.sh 在下一次收盘后照常跑。
EOF
