#!/usr/bin/env bash
#
# Daily backup: installed on both servers (cron, 12:00 Beijing time = around midnight US/Eastern, clear of
# every batch job and of the 06:00~07:00 automatic updates), but only the primary actually dumps; the
# standby logs one "skipped" line. Replication is not a backup -- drop a table by mistake and the standby
# drops it too.
#   One -Fc dump each of db_trader and db_trader_dev; a dump only counts as successful when pg_restore -l
#   can list it (and it has table data); the latest 7 of each are kept.
#   Directory and log: ~/db-backup/daily/, ~/db-backup/daily/backup.log
# Dumps are written as <name>.part and renamed only after verification, so retention (which globs *.dump)
# never counts or keeps a broken dump. Files get the default umask permissions.
set -uo pipefail
DIR="${HOME}/db-backup/daily"
KEEP=7
mkdir -p "${DIR}"
LOG="${DIR}/backup.log"
log() { echo "$(date '+%F %T') $*" >> "${LOG}"; }

role="$(psql -h localhost -U trader -d db_trader_dev -w -AtX -c 'select pg_is_in_recovery()' 2>/dev/null)"
if [[ "${role}" != f ]]; then
  log "本机不是主库（${role:-数据库连不上}），跳过"
  exit 0
fi

fail=0
stamp="$(date +%Y%m%d-%H%M)"
for db in db_trader db_trader_dev; do
  f="${DIR}/${db}-${stamp}.dump"
  if pg_dump -h localhost -U trader -w -Fc -f "${f}.part" "${db}" 2>> "${LOG}" \
      && tables="$(pg_restore -l "${f}.part" | grep -c ' TABLE DATA ')" && (( tables > 0 )); then
    mv "${f}.part" "${f}"
    log "${db} OK $(du -h "${f}" | cut -f1)，表 ${tables}"
  else
    rm -f "${f}.part"
    log "${db} 失败"
    fail=1
  fi
  ls -1t "${DIR}/${db}"-*.dump 2>/dev/null | tail -n +$((KEEP + 1)) | xargs -r rm -f
done
exit ${fail}
