#!/usr/bin/env bash
#
# Force-promotes the standby to primary when the primary is down (skipping the planned-switchover
# handshake). Use only when the primary's machine really is unavailable.
#   ./scripts/replica/promote.sh vps|shanghai
# Cost: writes not yet shipped to the standby before the primary went down are lost (a few seconds when
# the link was healthy; everything since the disconnect when the link was down).
# Afterwards: when the old primary comes back, **do not start the application on it** -- its PG comes up as
# a primary (data frozen at the moment it went down) and status.sh reports two primaries; rebuild it as a
# standby with scripts/replica/reseed.sh <old primary> (pg_rewind also worked in the 2026-10-19 drill but
# needs an extra superuser connection; a full rebuild of 3.3 GB takes about half an hour and is simple and
# reliable).
# The old side's state is judged from SSH + one SQL query; an empty SQL result only means the query failed,
# which is reported as "database not running" and then left to the operator's confirmation.
set -uo pipefail
cd "$(dirname "$0")/../.."
source scripts/replica/lib.sh
load_env

TARGET="${1:-}"
valid_node "${TARGET}" || { echo "用法: $0 vps|shanghai" >&2; exit 2; }
OLD="$(other "${TARGET}")"
[[ "$(sql "${TARGET}" "select pg_is_in_recovery()")" == t ]] || die "${TARGET} 不是备库"
if on "${OLD}" true 2>/dev/null; then
  r="$(sql "${OLD}" "select pg_is_in_recovery()" 2>/dev/null)"
  [[ "${r}" == f ]] && die "${OLD} 连得上、数据库也在当主库——这不是宕机，用 switchover.sh"
  say "⚠️ ${OLD} 连得上但数据库不在运行（${r:-连不上}）"
  app_running "${OLD}" && die "${OLD} 上应用还在跑，先停掉"
else
  say "${OLD} SSH 连不上"
fi
rcv="$(sql "${TARGET}" "select pg_last_wal_receive_lsn(), pg_last_wal_replay_lsn(), coalesce(pg_last_xact_replay_timestamp()::text, '无')")"
say "${TARGET} 已收到 / 已回放 / 最后一笔事务时间：${rcv}"
ask "确认 ${OLD} 已不可用、接受丢失未传过来的写入，请输入 promote ${TARGET}："
[[ "${ANSWER}" == "promote ${TARGET}" ]] || die "未确认（收到「${ANSWER}」，应为「promote ${TARGET}」），什么都没做"

on_tty "${TARGET}" "sudo -u postgres psql -AtX -v ON_ERROR_STOP=1 -c 'select pg_promote(true, 60)' -c \"select pg_drop_replication_slot(slot_name) from pg_replication_slots where slot_name = 'replica'\" -c \"select pg_create_physical_replication_slot('replica', true)\" >/dev/null" \
  || die "${TARGET} 提升失败，看日志"
[[ "$(sql "${TARGET}" "select pg_is_in_recovery()")" == f ]] || die "${TARGET} 提升后仍在恢复状态"
on "${TARGET}" "systemctl --user disable --now ${TUNNEL_UNIT} >/dev/null 2>&1 || true"
say "${TARGET} 已是主库。下一步：在 ${TARGET} 启动应用（~/trading-signal/bin/trader.sh start），网关与本机隧道照 switchover.sh 结尾的提醒做。"
say "${OLD} 恢复后：别启动它的应用，跑 scripts/replica/reseed.sh ${OLD}"
