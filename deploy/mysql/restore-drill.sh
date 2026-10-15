#!/bin/bash
# 恢复演练：把最新备份导入临时库，核对表集合与 Flyway 版本和源库一致，再删除临时库。
set -euo pipefail

drill() { # name host password database
  local latest
  latest=$(ls -1t /backups/"$1"-*.sql.gz 2>/dev/null | head -1)
  [ -n "$latest" ] || { echo "$1: no backup found" >&2; return 1; }
  local target="restore_drill_$1"
  export MYSQL_PWD="$3"
  mysql -h "$2" -uroot -e "DROP DATABASE IF EXISTS $target; CREATE DATABASE $target"
  gunzip -c "$latest" | mysql -h "$2" -uroot "$target"
  local q="SELECT COUNT(*) FROM information_schema.tables WHERE table_schema="
  local src_tables dst_tables src_ver dst_ver
  src_tables=$(mysql -h "$2" -uroot -N -e "$q'$4'")
  dst_tables=$(mysql -h "$2" -uroot -N -e "$q'$target'")
  src_ver=$(mysql -h "$2" -uroot -N -e "SELECT MAX(installed_rank) FROM \`$4\`.flyway_schema_history")
  dst_ver=$(mysql -h "$2" -uroot -N -e "SELECT MAX(installed_rank) FROM \`$target\`.flyway_schema_history")
  mysql -h "$2" -uroot -e "DROP DATABASE $target"
  echo "$1: $(basename "$latest") tables $dst_tables/$src_tables flyway rank $dst_ver/$src_ver"
  [ "$src_tables" = "$dst_tables" ] && [ "$src_ver" = "$dst_ver" ]
}

drill core mysql "$CORE_ROOT_PASSWORD" citycopilot
drill commerce commerce-mysql "$COMMERCE_ROOT_PASSWORD" citycopilot_commerce
echo "restore drill passed"
