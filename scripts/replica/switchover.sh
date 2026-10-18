#!/usr/bin/env bash
#
# 计划内主备切换：把系统从当前主库那台切到 <目标>（ARCHITECTURE §27、OPERATIONS「主备切换」）。
#   ./scripts/replica/switchover.sh vps|shanghai
# 要人在终端前：每台服务器上的 sudo 会提示输入口令。
#
# 顺序（2026-10-19 在上海用两个临时集群往返演练过）：
#   检查（两边可达、角色对、版本相同、备库 streaming）→ 停旧侧应用 → 旧侧 CHECKPOINT 取位置 → 等目标回放追平
#   → 旧侧正常关闭 PG → 核对目标收到的位置越过旧侧的关闭检查点（越不过就停在这里，旧侧原样可回滚）
#   → 提升目标、建复制槽、关目标的隧道 → 旧侧开隧道指向目标、以备库身份启动、删掉身上残留的槽
#   → 核对旧侧已 streaming → 目标启动应用。
# 计划内切换不需要 pg_rewind：旧主正常关闭时会把最后的 WAL（含关闭检查点）发完，新时间线正好从那里分叉。
#
# 不做的事（打印提醒，人工做）：网关（盈透同一用户名只能一个会话、富途同账号多台 OpenD 会互踢行情）、本机隧道改指。
set -uo pipefail
cd "$(dirname "$0")/../.."
source scripts/replica/lib.sh
load_env

TARGET="${1:-}"
valid_node "$TARGET" || { echo "用法: $0 vps|shanghai" >&2; exit 2; }
OLD="$(other "$TARGET")"

# ------------------------------------------------------------------ 检查（只读）
say "检查：$OLD（现主库）→ $TARGET（目标）"
on "$OLD" true || die "$OLD 连不上。主库所在机器已不可用时不能走计划内切换，见 OPERATIONS「主库宕机」"
on "$TARGET" true || die "$TARGET 连不上"
[[ "$(sql "$OLD" "select pg_is_in_recovery()")" == f ]] || die "$OLD 不是主库"
[[ "$(sql "$TARGET" "select pg_is_in_recovery()")" == t ]] || die "$TARGET 不是备库"
d_old="$(app_dir "$OLD")"; d_new="$(app_dir "$TARGET")"
[[ -n "$d_new" ]] || die "$TARGET 上没有 ~/trading-signal（先把当前版本部署过去）"
[[ "${d_old%-*-*}" == "${d_new%-*-*}" ]] || die "两边应用版本不同：$OLD=$d_old，$TARGET=$d_new（先把同一版本部署到 $TARGET）"
app_running "$TARGET" && die "$TARGET 上已经有应用在跑"
st="$(sql "$TARGET" "select status from pg_stat_wal_receiver")"
[[ "$st" == streaming ]] || die "$TARGET 的 WAL 接收状态是「${st:-没有}」，不是 streaming"
lag="$(sql "$OLD" "select pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn)::bigint from pg_stat_replication where application_name = '$TARGET'")"
[[ -n "$lag" ]] || die "$OLD 上看不到备库 $TARGET"
say "检查通过：版本 ${d_new%-*-*}，$TARGET streaming，回放落后 $lag 字节"
echo
echo "将要：停 $OLD 的应用与数据库 → 提升 $TARGET 为主库 → $OLD 改为备库 → 在 $TARGET 启动应用。"
read -r -p "确认请输入目标名 $TARGET：" ans
[[ "$ans" == "$TARGET" ]] || die "未确认，什么都没做"

# ------------------------------------------------------------------ 旧侧：停应用、取位置
say "停 $OLD 的应用"
on "$OLD" '~/trading-signal/bin/trader.sh stop' || true
for i in $(seq 1 30); do app_running "$OLD" || break; sleep 2; done
app_running "$OLD" && die "$OLD 的应用没停下来（看 ~/trading-signal/logs），没有动数据库"

say "$OLD：CHECKPOINT 并取当前位置（输入 $OLD 的 sudo 口令）"
on_tty "$OLD" "sudo -u postgres psql -AtX -c 'CHECKPOINT' -c 'select pg_current_wal_lsn()' > /tmp/pg-switch.lsn" \
  || die "$OLD 上 CHECKPOINT 失败；应用已停，数据库未动。恢复运行：在 $OLD 执行 ~/trading-signal/bin/trader.sh start"
LSN="$(on "$OLD" 'tail -1 /tmp/pg-switch.lsn')"
[[ "$LSN" =~ ^[0-9A-F]+/[0-9A-F]+$ ]] || die "取到的位置不对：$LSN"

for i in $(seq 1 60); do
  [[ "$(sql "$TARGET" "select pg_last_wal_replay_lsn() >= '$LSN'::pg_lsn")" == t ]] && break
  sleep 2
done
[[ "$(sql "$TARGET" "select pg_last_wal_replay_lsn() >= '$LSN'::pg_lsn")" == t ]] \
  || die "$TARGET 两分钟内没追到 $LSN；应用已停、数据库未动。恢复运行：在 $OLD 执行 ~/trading-signal/bin/trader.sh start"
say "$TARGET 已回放到 $LSN"

# ------------------------------------------------------------------ 旧侧：正常关闭 PG，核对关闭检查点已到目标
say "$OLD：正常关闭数据库（输入 sudo 口令）"
on_tty "$OLD" "sudo bash -c 'systemctl stop $PG_UNIT && /usr/lib/postgresql/18/bin/pg_controldata $PG_DATA' > /tmp/pg-switch.ctl" \
  || die "$OLD 关闭数据库失败；在 $OLD 查看 systemctl status $PG_UNIT"
state="$(on "$OLD" "awk -F': *' '/Database cluster state/{print \$2}' /tmp/pg-switch.ctl")"
CKPT="$(on "$OLD" "awk -F': *' '/Latest checkpoint location/{print \$2}' /tmp/pg-switch.ctl")"
[[ "$state" == "shut down" ]] || die "$OLD 的集群状态是「$state」，不是正常关闭"
say "$OLD 已正常关闭，关闭检查点 $CKPT"
recv="$(sql "$TARGET" "select pg_last_wal_receive_lsn() > '$CKPT'::pg_lsn")"
if [[ "$recv" != t ]]; then
  die "$TARGET 没收到 $OLD 的关闭检查点（收到 $(sql "$TARGET" "select pg_last_wal_receive_lsn()")），停止。
    $OLD 的数据完整、可以原样回滚：在 $OLD 执行 sudo systemctl start $PG_UNIT 与 ~/trading-signal/bin/trader.sh start"
fi

# ------------------------------------------------------------------ 目标：提升、建槽、关隧道
say "$TARGET：提升为主库并建复制槽（输入 $TARGET 的 sudo 口令）"
on_tty "$TARGET" "sudo -u postgres psql -AtX -v ON_ERROR_STOP=1 -c 'select pg_promote(true, 60)' -c \"select pg_drop_replication_slot(slot_name) from pg_replication_slots where slot_name = 'replica'\" -c \"select pg_create_physical_replication_slot('replica', true)\" >/dev/null" \
  || die "$TARGET 提升失败——此时两边数据库都没在当主库，先在 $TARGET 查看日志"
[[ "$(sql "$TARGET" "select pg_is_in_recovery()")" == f ]] || die "$TARGET 提升后仍在恢复状态"
on "$TARGET" "systemctl --user disable --now $TUNNEL_UNIT >/dev/null 2>&1 || true"
say "$TARGET 已是主库"

# ------------------------------------------------------------------ 旧侧：改成备库
say "$OLD：隧道指向 $TARGET，以备库身份启动（输入 sudo 口令）"
on "$OLD" "systemctl --user enable --now $TUNNEL_UNIT >/dev/null 2>&1" || die "$OLD 隧道起不来；先跑 scripts/replica/install-node.sh $OLD"
for i in $(seq 1 15); do on "$OLD" 'pg_isready -q -h 127.0.0.1 -p 15432' && break; sleep 2; done
on "$OLD" 'pg_isready -q -h 127.0.0.1 -p 15432' || die "$OLD 经隧道连不上 $TARGET 的数据库（$TARGET 已是主库、应用未启动）"
on_tty "$OLD" "sudo bash -c 'install -o postgres -g postgres -m 600 /dev/null $PG_DATA/standby.signal && systemctl start $PG_UNIT' && sleep 3 && sudo -u postgres psql -AtX -c \"select pg_drop_replication_slot(slot_name) from pg_replication_slots where slot_name = 'replica'\" >/dev/null" \
  || say "⚠️ $OLD 改备库这一步有报错，切换继续，稍后用 status.sh 核对"
for i in $(seq 1 30); do
  [[ "$(sql "$TARGET" "select count(*) from pg_stat_replication where application_name = '$OLD' and state = 'streaming'")" == 1 ]] && break
  sleep 2
done
if [[ "$(sql "$TARGET" "select count(*) from pg_stat_replication where application_name = '$OLD' and state = 'streaming'")" == 1 ]]; then
  say "$OLD 已作为备库接上 $TARGET（streaming）"
else
  say "⚠️ $OLD 没有接上；切换继续，之后用 scripts/replica/reseed.sh $OLD 重建备库"
fi

# ------------------------------------------------------------------ 目标：启动应用
say "$TARGET：启动应用"
on "$TARGET" '~/trading-signal/bin/trader.sh start' || die "$TARGET 应用启动失败，看 ~/trading-signal/logs"
health="$(on "$TARGET" "curl -s -m 10 http://127.0.0.1:8093/actuator/health" 2>/dev/null)"
say "$TARGET 健康：${health:0:200}"
on "$OLD" 'rm -f /tmp/pg-switch.lsn /tmp/pg-switch.ctl' || true

cat <<EOF

✅ 数据库已切到 $TARGET，应用已在 $TARGET 启动。还要人工做：
  1. 网关：在 $OLD 退出 IB Gateway、停 OpenD（盈透同一用户名只能一个会话，富途会互踢行情）；在 $TARGET 登录 IB Gateway、启 OpenD。
  2. 本机隧道改指 $TARGET（8093 / 5432 / 4001 / 11111）。
  3. ./scripts/replica/status.sh 确认；./scripts/check-daily.sh 在下一次收盘后照常跑。
EOF
