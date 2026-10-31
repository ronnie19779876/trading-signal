#!/usr/bin/env bash
#
# Rebuilds one node as a standby: full sync from the current primary (about 3.3 GB; roughly half an hour
# at the bandwidth measured on 2026-10-19).
#   ./scripts/replica/reseed.sh vps|shanghai
# For: the old primary returning after an outage and a forced promotion; a replication slot invalidated for
# exceeding the retention limit; a broken standby data directory.
# The old data directory is renamed to main.reseed-<time> and kept; delete it by hand once the new standby
# is confirmed healthy.
# Guards: the other node must be a primary, the application must not be running on this node (protects
# the node holding the live data in a split brain), and the operator must type "reseed <node>".
# pg_basebackup is run without -R on purpose: -R writes primary_conninfo into postgresql.auto.conf inside
# the data directory; node-specific settings live in /etc/postgresql/18/main/conf.d/ instead.
set -uo pipefail
cd "$(dirname "$0")/../.."
source scripts/replica/lib.sh
load_env

NODE="${1:-}"
valid_node "${NODE}" || { echo "用法: $0 vps|shanghai" >&2; exit 2; }
PRIMARY="$(other "${NODE}")"
[[ "$(sql "${PRIMARY}" "select pg_is_in_recovery()")" == f ]] || die "${PRIMARY} 不是主库，没有可以同步的来源"
app_running "${NODE}" && die "${NODE} 上应用在跑——如果它是宕机后回来的旧主库，先确认要丢弃它宕机后写的数据，再手工停掉应用"
r="$(sql "${NODE}" "select pg_is_in_recovery()" 2>/dev/null)"
say "${NODE} 当前数据库状态：${r:-没在运行}（t=备库，f=主库）"
ask "将清空 ${NODE} 的数据库（原目录改名保留）并从 ${PRIMARY} 全量同步，请输入 reseed ${NODE}："
[[ "${ANSWER}" == "reseed ${NODE}" ]] || die "未确认（收到「${ANSWER}」，应为「reseed ${NODE}」），什么都没做"

say "${PRIMARY}：重建复制槽（输入 ${PRIMARY} 的 sudo 口令）"
on_tty "${PRIMARY}" "sudo -u postgres psql -AtX -v ON_ERROR_STOP=1 -c \"select pg_drop_replication_slot(slot_name) from pg_replication_slots where slot_name = 'replica' and not active\" -c \"select pg_create_physical_replication_slot('replica', true) where not exists (select 1 from pg_replication_slots where slot_name = 'replica')\" >/dev/null" \
  || die "${PRIMARY} 上建复制槽失败"
on "${PRIMARY}" "systemctl --user disable --now ${TUNNEL_UNIT} >/dev/null 2>&1 || true"
on "${NODE}" "systemctl --user enable --now ${TUNNEL_UNIT} >/dev/null 2>&1" || die "${NODE} 隧道起不来；先跑 install-node.sh ${NODE}"
for i in $(seq 1 15); do on "${NODE}" 'pg_isready -q -h 127.0.0.1 -p 15432' && break; sleep 2; done
on "${NODE}" 'pg_isready -q -h 127.0.0.1 -p 15432' || die "${NODE} 经隧道连不上 ${PRIMARY} 的数据库"

TS="$(date +%Y%m%d-%H%M)"
say "${NODE}：停库、原目录改名、全量同步、以备库启动（输入 ${NODE} 的 sudo 口令；同步时显示进度）"
on_tty "${NODE}" "sudo bash -c 'set -e; systemctl stop ${PG_UNIT}; mv ${PG_DATA} ${PG_DATA}.reseed-${TS}; install -d -o postgres -g postgres -m 700 ${PG_DATA}; sudo -u postgres -H pg_basebackup -h 127.0.0.1 -p 15432 -U replicator -D ${PG_DATA} -S replica -X stream -c fast -P; install -o postgres -g postgres -m 600 /dev/null ${PG_DATA}/standby.signal; systemctl start ${PG_UNIT}'" \
  || die "${NODE} 重建失败。原数据目录在 ${PG_DATA}.reseed-${TS}"
for i in $(seq 1 30); do
  [[ "$(sql "${PRIMARY}" "select count(*) from pg_stat_replication where application_name = '${NODE}' and state = 'streaming'")" == 1 ]] && break
  sleep 2
done
[[ "$(sql "${PRIMARY}" "select count(*) from pg_stat_replication where application_name = '${NODE}' and state = 'streaming'")" == 1 ]] \
  || die "${NODE} 启动了但没接上 ${PRIMARY}，看 ${NODE} 的 /var/log/postgresql/postgresql-18-main.log"
say "✅ ${NODE} 已作为备库接上 ${PRIMARY}。确认无误后删除原目录：sudo rm -rf ${PG_DATA}.reseed-${TS}"
