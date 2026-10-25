#!/usr/bin/env bash
#
# 主库宕机时把备库强制提升为主库（不走计划内切换的握手）。只在主库那台确实不可用时用。
#   ./scripts/replica/promote.sh vps|shanghai
# 代价：主库宕机前还没传到备库的写入丢失（链路正常时是几秒，链路断着时是断开期间的全部）。
# 之后：原主库恢复时**不要启动它上面的应用**——它的 PG 会以主库身份起来（数据停在宕机那一刻），
# status.sh 会报两个主库；用 scripts/replica/reseed.sh <原主库> 把它重建成备库（2026-10-19 演练里 pg_rewind 也行，
# 但需要额外的超级用户连接；全量重建 3.3 GB 约半小时，简单可靠）。
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
