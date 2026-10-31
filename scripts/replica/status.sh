#!/usr/bin/env bash
#
# Primary/standby status (read-only): the role of each node, where the application runs, whether
# replication is flowing, how far behind it is, and how much WAL the replication slot is holding back.
#   ./scripts/replica/status.sh
# Exit codes: 0 healthy; 1 problems (two primaries, application running on the standby, replication broken,
# etc.); 2 a node is unreachable.
#
# Rules: exactly one primary; the application runs only on the primary; the primary has one streaming
# standby; the tunnel is open only on the standby side; warn when the slot holds more than 5 GB of WAL
# (the limit is 20 GB; beyond it the slot is invalidated and a full resync is required).
set -uo pipefail
cd "$(dirname "$0")/../.."
source scripts/replica/lib.sh
load_env

PROBLEM=0
UNREACHABLE=0
# The local machine runs macOS's bundled bash 3.2, which has no associative arrays: use variables such as
# ROLE_vps, read and written through set_v / get_v
set_v() { printf -v "$1_$2" '%s' "$3"; }
get_v() { local v="$1_$2"; echo "${!v:-}"; }
warn() { echo "  ⚠️  $*"; [[ ${PROBLEM} -eq 0 ]] && PROBLEM=1; }
bad() { echo "  ❌ $*"; PROBLEM=1; }

for n in "${NODES[@]}"; do
  echo "== ${n}"
  r="$(sql "${n}" "select case when pg_is_in_recovery() then 'standby' else 'primary' end" 2>/dev/null)"
  if [[ -z "${r}" ]]; then
    if on "${n}" true 2>/dev/null; then set_v ROLE "${n}" down; bad "数据库连不上（PG 没在运行？）"
    else set_v ROLE "${n}" unreachable; echo "  ❌ SSH 连不上"; UNREACHABLE=1; continue; fi
  else
    set_v ROLE "${n}" "${r}"
  fi
  set_v DIR "${n}" "$(app_dir "${n}")"
  if app_running "${n}"; then set_v APP "${n}" running; else set_v APP "${n}" stopped; fi
  set_v TUNNEL "${n}" "$(on "${n}" "systemctl --user is-active ${TUNNEL_UNIT}" 2>/dev/null)"
  echo "  角色 $(get_v ROLE "${n}")；应用 $(get_v APP "${n}")（$(get_v DIR "${n}")）；隧道 $(get_v TUNNEL "${n}")"

  if [[ "$(get_v ROLE "${n}")" == primary ]]; then
    rows="$(sql "${n}" "select application_name, state, pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn)::bigint, coalesce(extract(epoch from replay_lag)::numeric(10,1)::text, '0') from pg_stat_replication where application_name <> 'pg_basebackup'")"
    if [[ -z "${rows}" ]]; then
      bad "没有备库连着（复制断了，或备库在做全量同步）"
    else
      while IFS='|' read -r app state lagb lags; do
        echo "  备库 ${app}：${state}，回放落后 ${lagb} 字节 / ${lags} 秒"
        [[ "${state}" == streaming ]] || warn "备库 ${app} 状态是 ${state}"
      done <<< "${rows}"
    fi
    slot="$(sql "${n}" "select active, wal_status, pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)::bigint from pg_replication_slots where slot_name = 'replica'")"
    if [[ -z "${slot}" ]]; then
      bad "没有复制槽 replica"
    else
      IFS='|' read -r active wst kept <<< "${slot}"
      echo "  复制槽 replica：active=${active}，wal_status=${wst}，压着 $((kept / 1048576)) MB WAL"
      (( kept > 5 * 1024 * 1024 * 1024 )) && warn "复制槽压着的 WAL 超过 5 GB（上限 20 GB）"
      [[ "${wst}" == lost ]] && bad "复制槽已作废（超过保留上限），备库要重做全量同步：scripts/replica/reseed.sh"
    fi
    [[ "$(get_v TUNNEL "${n}")" == active ]] && warn "主库这边的隧道开着（应只在备库那边开）"
  elif [[ "$(get_v ROLE "${n}")" == standby ]]; then
    rcv="$(sql "${n}" "select status, coalesce(extract(epoch from now() - last_msg_receipt_time)::int, -1) from pg_stat_wal_receiver")"
    if [[ -z "${rcv}" ]]; then
      bad "备库没有在接收 WAL（walreceiver 不在）"
    else
      IFS='|' read -r st age <<< "${rcv}"
      echo "  接收：${st}，最近一次收到主库消息 ${age} 秒前"
      [[ "${st}" == streaming ]] || bad "接收状态是 ${st}"
      (( age > 120 )) && warn "超过 2 分钟没收到主库消息"
    fi
    [[ "$(get_v TUNNEL "${n}")" == active ]] || bad "备库这边的隧道没开"
    [[ "$(get_v APP "${n}")" == running ]] && bad "应用跑在备库上（应当起不来；3.4.5 起环境守卫会拒绝）"
  fi
done

echo "== 结论"
primaries=0
for n in "${NODES[@]}"; do [[ "$(get_v ROLE "${n}")" == primary ]] && primaries=$((primaries + 1)); done
if (( primaries == 0 )); then bad "没有主库"
elif (( primaries > 1 )); then bad "两边都是主库（脑裂）：先停掉不该运行的那边的应用，再用 scripts/replica/reseed.sh 把它重建成备库"
fi
[[ "$(get_v DIR vps)" != "$(get_v DIR shanghai)" ]] && warn "两边应用版本不同：vps=$(get_v DIR vps)，shanghai=$(get_v DIR shanghai)（切换前要先把同一版本部署到两边）"
if (( UNREACHABLE )); then echo "  有节点连不上"; exit 2; fi
if (( PROBLEM )); then exit 1; fi
echo "  ✅ 正常"
