#!/usr/bin/env bash
#
# Standby watchdog: installed on both servers (install-node.sh), run every 2 minutes by a systemd user
# timer; what it does depends on the local node's role.
#   Standby: start the tunnel if it is not running; if it runs but the WAL receiver is not streaming, or no
#            message has come from the primary for over 5 minutes, restart the tunnel.
#            Why: one way an SSH tunnel breaks is that the process stays alive and the local port keeps
#            listening while nothing gets forwarded (measured on the Shanghai link in 2026-09); ServerAlive
#            probes do not catch it and systemd does not know it is broken -- only replication itself shows it.
#   Primary: does nothing (a primary connects to no one; an open tunnel is only reported).
#   Database unreachable (e.g. stopped during a reseed or switchover): does nothing.
# Output goes to the journal: journalctl --user -u pg-replica-watchdog
set -uo pipefail
TUNNEL=pg-replica-tunnel.service
q() { psql -h localhost -U trader -d db_trader_dev -w -AtX -c "$1" 2>/dev/null; }

case "$(q "select pg_is_in_recovery()")" in
  t)
    row="$(q "select status, coalesce(extract(epoch from now() - last_msg_receipt_time)::int, 99999) from pg_stat_wal_receiver")"
    st="${row%%|*}"; age="${row##*|}"
    if ! systemctl --user is-active -q "${TUNNEL}"; then
      echo "备库：隧道没在运行，启动"
      systemctl --user start "${TUNNEL}"
    elif [[ -z "${row}" || "${st}" != streaming || "${age:-99999}" -gt 300 ]]; then
      echo "备库：WAL 接收异常（${row:-没有 walreceiver}），重启隧道"
      systemctl --user restart "${TUNNEL}"
    fi
    ;;
  f)
    if systemctl --user is-active -q "${TUNNEL}"; then
      echo "主库：隧道开着（主库不该连别人），请检查是否切换没收尾"
    fi
    ;;
  *)
    echo "本机数据库连不上，跳过"
    ;;
esac
