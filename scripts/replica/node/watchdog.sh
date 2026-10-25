#!/usr/bin/env bash
#
# 备库看门狗：在两台服务器上都装（install-node.sh），由 systemd 用户定时器每 2 分钟跑一次，按本机角色决定做什么。
#   备库：隧道没在跑就拉起；在跑但 WAL 接收不在 streaming、或超过 5 分钟没收到主库消息，就重启隧道。
#         起因：SSH 隧道有一种坏法是进程还活着、本地端口还在监听、转发全部不通（2026-09 上海链路实测），
#         ServerAlive 探测救不了，systemd 也不知道它坏了——只有看复制本身才知道。
#   主库：什么都不做（主库不连别人；隧道开着只提示）。
# 输出进 journal：journalctl --user -u pg-replica-watchdog
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
