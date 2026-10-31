#!/usr/bin/env bash
# Shared part of the primary/standby switchover scripts (sourced; ARCHITECTURE §27, OPERATIONS
# "primary/standby switchover" section).
# Runs on the local operator machine and drives the two servers over SSH.
# Must stay compatible with macOS's bundled bash 3.2: no associative arrays, and always write variables as
# ${...} -- under a UTF-8 locale bash 3.2 treats the bytes of an adjacent full-width character as part of
# the variable name (unbound variable).
#
# Host details live only in the local ~/.config/trading-signal/replica.env (never in the repository):
#   vps_ssh=admin@<address>           vps_port=<port>
#   shanghai_ssh=devops@<address>     shanghai_port=<port>
# The tunnel between the two servers uses the same entries: the standby connects to the primary using the
# other node's *_ssh / *_port.

REPLICA_ENV="${REPLICA_ENV:-${HOME}/.config/trading-signal/replica.env}"
NODES=(vps shanghai)
PG_DATA=/var/lib/postgresql/18/main
PG_UNIT=postgresql@18-main
TUNNEL_UNIT=pg-replica-tunnel.service

load_env() {
  if [[ ! -f "${REPLICA_ENV}" ]]; then
    echo "缺少 ${REPLICA_ENV}（格式见 scripts/replica/lib.sh 开头）" >&2
    exit 2
  fi
  # shellcheck disable=SC1090
  source "${REPLICA_ENV}"
  local n v
  for n in "${NODES[@]}"; do
    v="${n}_ssh"
    [[ -n "${!v:-}" ]] || { echo "${REPLICA_ENV} 缺 ${n}_ssh" >&2; exit 2; }
  done
}

valid_node() { [[ "${1:-}" == vps || "${1:-}" == shanghai ]]; }
other() { [[ "$1" == vps ]] && echo shanghai || echo vps; }
ssh_target() { local v="${1}_ssh"; echo "${!v}"; }
ssh_port() { local v="${1}_port"; echo "${!v:-22}"; }

# Runs a command on a node without allocating a terminal (no interaction possible). It does <b>not read
# local standard input</b> (ssh -n): in the 2026-10-25 drill an ssh call during the check phase swallowed
# the "shanghai" the user had typed into the terminal ahead of time, so the confirmation prompt never saw
# it. Use on_in to feed data to the remote side.
# ssh joins the arguments into one remote shell command line, so callers only pass constant strings plus
# values that were validated beforehand (node names, LSNs matched against a regex).
on() {
  local n="$1"; shift
  ssh -n -o BatchMode=yes -o ConnectTimeout=20 -p "$(ssh_port "${n}")" "$(ssh_target "${n}")" "$@"
}

# Like on, but forwards local standard input to the remote side (for feeding data via a pipe / redirect).
on_in() {
  local n="$1"; shift
  ssh -o BatchMode=yes -o ConnectTimeout=20 -p "$(ssh_port "${n}")" "$(ssh_target "${n}")" "$@"
}

# Asks the human for confirmation: reads straight from the terminal (when there is one), after draining
# anything typed ahead of time -- so nothing gets confirmed automatically before the summary was read.
# Without a terminal (script driven by a pipe) it falls back to standard input. The answer goes into the
# global ANSWER.
ask() {
  ANSWER=""
  if { exec 9</dev/tty; } 2>/dev/null; then
    local junk
    while read -r -t 1 junk <&9; do :; done
    read -r -p "$1" ANSWER <&9 || true
    exec 9<&-
  else
    read -r -p "$1" ANSWER || true
  fi
}

# Allocates a terminal, for steps where a human has to type the sudo password. The sudo prompt gets mixed
# into the output, so **never capture its output** -- to get a result, have the remote side write a file
# and read it back with on.
on_tty() {
  local n="$1"; shift
  ssh -t -o ConnectTimeout=20 -p "$(ssh_port "${n}")" "$(ssh_target "${n}")" "$@"
}

# Runs one read-only SQL statement on a node as trader (password from the node's own ~/.pgpass; trader
# has pg_monitor, so it can see replication state).
# Connects to the dev database: everything queried is cluster-level state, independent of the database.
# The SQL goes through standard input so it does not have to survive two layers of quoting.
# Prints nothing when the query fails for any reason (SSH, connection, auth): callers must not read an
# empty result as "the database is down".
sql() {
  local n="$1" q="$2"
  printf '%s\n' "${q}" | on_in "${n}" "psql -h localhost -U trader -d db_trader_dev -w -AtX -F'|' -v ON_ERROR_STOP=1 -f -"
}

# Whether the application is running on a node: a java process with trader-app.jar, or something listening
# on 8093 -- either one counts as running.
# Do not use pgrep -f to find the process: the remote command line contains the pattern and matches itself
# (that caused one false positive during the migration).
# Returns 0 = running, 1 = not running, 2 = cannot tell (SSH failed or the output is not two counts).
# Until 3.5.2 a failed SSH call produced no output and read as "not running"; promote.sh then went on
# towards promoting while the old primary's application might still be writing (found in the
# 2026-10-31 review). Callers written as `app_running X && die` treat 2 like 1; callers that must not
# proceed on an unknown answer check for 2 explicitly.
app_running() {
  local out p l
  out="$(on "$1" "ps -eo comm,args | awk '\$1==\"java\" && /trader-app\\.jar/' | grep -c . ; ss -ltn | grep -c ':8093 '" 2>/dev/null)"
  p="$(printf '%s\n' "${out}" | sed -n 1p)"
  l="$(printf '%s\n' "${out}" | sed -n 2p)"
  [[ "${p}" =~ ^[0-9]+$ && "${l}" =~ ^[0-9]+$ ]] || return 2
  (( p > 0 || l > 0 ))
}

# Name of the application directory the node's symlink points to (trading-signal-<version>-<timestamp>).
app_dir() { on "$1" 'basename "$(readlink -f ~/trading-signal 2>/dev/null)"' 2>/dev/null; }

say() { printf '[%s] %s\n' "$(date +%T)" "$*"; }
die() { printf '[%s] ❌ %s\n' "$(date +%T)" "$*" >&2; exit 1; }
