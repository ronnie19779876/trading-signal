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
# The old side's state is judged from SSH, one SQL query and systemd: when SSH works, the script only
# continues if systemd reports the PostgreSQL unit stopped (inactive / failed) and the application is
# known to be stopped. A failed query or an unanswered check stops the script instead of being read as
# "down". When SSH does not work at all, the operator's confirmation is the only fence.
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
  [[ "${r}" == t ]] && die "${OLD} 的数据库在当备库——两边都是备库，不是宕机；先用 status.sh 查清"
  # A failed query is not proof that the database is down (flaky link, too many connections, auth):
  # until 3.5.2 an empty answer was read as "not running" and the script went on to promote, which
  # can leave two primaries both taking writes (found in the 2026-10-31 review). Only systemd saying
  # the unit is stopped counts; any other state, or no answer at all, stops here.
  pg="$(on "${OLD}" "systemctl is-active ${PG_UNIT}" 2>/dev/null)"
  case "${pg}" in
    inactive|failed) say "⚠️ ${OLD} 连得上，数据库没在运行（systemctl：${pg}）" ;;
    "") die "无法判断 ${OLD} 上数据库是否在运行（查询失败，SSH 查 systemctl 也没有回答），不能按宕机处理" ;;
    *) die "${OLD} 数据库在运行但查询失败（systemctl：${pg}，查询结果：${r:-空}），不能按宕机处理；查清原因或先在 ${OLD} 上 sudo systemctl stop ${PG_UNIT}" ;;
  esac
  app_running "${OLD}"
  case $? in
    0) die "${OLD} 上应用还在跑，先停掉" ;;
    2) die "无法判断 ${OLD} 上应用是否在跑（SSH 没有回答），不能按已停处理" ;;
  esac
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
