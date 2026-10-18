#!/usr/bin/env bash
# 主备互换脚本的公共部分（供 source；ARCHITECTURE §27、OPERATIONS「主备切换」）。在本机（运维机）上跑，经 SSH 操作两台服务器。
#
# 主机信息只放本机 ~/.config/trading-signal/replica.env（不进仓库），格式：
#   vps_ssh=admin@<地址>           vps_port=22
#   shanghai_ssh=devops@<地址>     shanghai_port=2022
# 两台服务器之间的隧道也用这两行：备库那边连主库那边，用的就是对方的 *_ssh / *_port。

REPLICA_ENV="${REPLICA_ENV:-$HOME/.config/trading-signal/replica.env}"
NODES=(vps shanghai)
PG_DATA=/var/lib/postgresql/18/main
PG_UNIT=postgresql@18-main
TUNNEL_UNIT=pg-replica-tunnel.service

load_env() {
  if [[ ! -f "$REPLICA_ENV" ]]; then
    echo "缺少 $REPLICA_ENV（格式见 scripts/replica/lib.sh 开头）" >&2
    exit 2
  fi
  # shellcheck disable=SC1090
  source "$REPLICA_ENV"
  local n v
  for n in "${NODES[@]}"; do
    v="${n}_ssh"
    [[ -n "${!v:-}" ]] || { echo "$REPLICA_ENV 缺 ${n}_ssh" >&2; exit 2; }
  done
}

valid_node() { [[ "${1:-}" == vps || "${1:-}" == shanghai ]]; }
other() { [[ "$1" == vps ]] && echo shanghai || echo vps; }
ssh_target() { local v="${1}_ssh"; echo "${!v}"; }
ssh_port() { local v="${1}_port"; echo "${!v:-22}"; }

# 在节点上执行，不分配终端（不能有交互）。
on() {
  local n="$1"; shift
  ssh -o BatchMode=yes -o ConnectTimeout=20 -p "$(ssh_port "$n")" "$(ssh_target "$n")" "$@"
}

# 需要人输入 sudo 口令时用：分配终端。输出会混进 sudo 提示，所以**不要捕获它的输出**——
# 要取结果就让远端写文件，再用 on 读回来。
on_tty() {
  local n="$1"; shift
  ssh -t -o ConnectTimeout=20 -p "$(ssh_port "$n")" "$(ssh_target "$n")" "$@"
}

# 以 trader 在节点上执行一条只读 SQL（走节点本机 ~/.pgpass；trader 有 pg_monitor，能看复制状态）。
# 用开发库连接：查的都是集群级状态，与连哪个库无关。SQL 走标准输入，免得穿过两层引号。
sql() {
  local n="$1" q="$2"
  printf '%s\n' "$q" | on "$n" "psql -h localhost -U trader -d db_trader_dev -w -AtX -F'|' -v ON_ERROR_STOP=1 -f -"
}

# 节点上应用是否在跑：有 trader-app.jar 的 java 进程或 8093 在监听，任一即算在跑。
# 判断进程别用 pgrep -f：远端命令行里含模式，会匹配到自己（迁移时误报过一次）。
app_running() {
  on "$1" "ps -eo comm,args | awk '\$1==\"java\" && /trader-app\\.jar/' | grep -c . ; ss -ltn | grep -c ':8093 '" 2>/dev/null \
    | awk 'NR==1{p=$1} NR==2{l=$1} END{exit !((p+0)>0 || (l+0)>0)}'
}

# 节点上软链指向的应用目录名（trading-signal-<版本>-<时间戳>）。
app_dir() { on "$1" 'basename "$(readlink -f ~/trading-signal 2>/dev/null)"' 2>/dev/null; }

say() { printf '[%s] %s\n' "$(date +%T)" "$*"; }
die() { printf '[%s] ❌ %s\n' "$(date +%T)" "$*" >&2; exit 1; }
