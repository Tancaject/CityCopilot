#!/bin/bash
# 定时逻辑备份 Core 与 Commerce 两个库；--single-transaction 对 InnoDB 给出一致快照且不锁表。
# 不带 --databases，备份里没有库名，恢复时必须显式指定目标库，不会误写线上库。
# Commerce Redis 不单独备份：库存与准入状态以 MySQL 账本为准，可由 inventory bootstrap 重建。
set -uo pipefail

dump() { # name host password database
  local file="/backups/$1-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
  if MYSQL_PWD="$3" mysqldump -h "$2" -uroot --single-transaction --quick --routines --triggers \
      --set-gtid-purged=OFF "$4" | gzip > "$file.tmp"; then
    mv "$file.tmp" "$file"
    echo "backup ok: $file ($(stat -c %s "$file") bytes)"
  else
    rm -f "$file.tmp"
    echo "backup FAILED: $1" >&2
  fi
}

while true; do
  dump core mysql "$CORE_ROOT_PASSWORD" citycopilot
  dump commerce commerce-mysql "$COMMERCE_ROOT_PASSWORD" citycopilot_commerce
  find /backups -name '*.sql.gz' -mtime +"$BACKUP_RETENTION_DAYS" -delete
  sleep "$BACKUP_INTERVAL_SECONDS"
done
