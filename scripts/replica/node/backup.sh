#!/usr/bin/env bash
#
# 每日备份：两台服务器都装（cron，北京 12:00 = 美东零点前后，避开所有跑批与 06:00~07:00 的自动更新），
# 只在主库那边真正导出；备库那边记一行「跳过」。复制不是备份——误删一张表，备库会跟着删。
#   db_trader、db_trader_dev 各导一份 -Fc，pg_restore -l 能列出才算成功，各保留最近 7 份。
#   目录与日志：~/db-backup/daily/、~/db-backup/daily/backup.log
set -uo pipefail
DIR="$HOME/db-backup/daily"
KEEP=7
mkdir -p "$DIR"
LOG="$DIR/backup.log"
log() { echo "$(date '+%F %T') $*" >> "$LOG"; }

role="$(psql -h localhost -U trader -d db_trader_dev -w -AtX -c 'select pg_is_in_recovery()' 2>/dev/null)"
if [[ "$role" != f ]]; then
  log "本机不是主库（${role:-数据库连不上}），跳过"
  exit 0
fi

fail=0
stamp="$(date +%Y%m%d-%H%M)"
for db in db_trader db_trader_dev; do
  f="$DIR/$db-$stamp.dump"
  if pg_dump -h localhost -U trader -w -Fc -f "$f.part" "$db" 2>> "$LOG" \
      && tables="$(pg_restore -l "$f.part" | grep -c ' TABLE DATA ')" && (( tables > 0 )); then
    mv "$f.part" "$f"
    log "$db OK $(du -h "$f" | cut -f1)，表 $tables"
  else
    rm -f "$f.part"
    log "$db 失败"
    fail=1
  fi
  ls -1t "$DIR/$db"-*.dump 2>/dev/null | tail -n +$((KEEP + 1)) | xargs -r rm -f
done
exit $fail
