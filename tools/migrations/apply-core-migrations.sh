#!/bin/sh
set -eu

export MYSQL_PWD="${MYSQL_PASSWORD}"

constraint_count="$(
  mysql -u"${MYSQL_USER}" "${MYSQL_DATABASE}" --batch --skip-column-names <<'SQL'
SELECT COUNT(*)
FROM information_schema.TABLE_CONSTRAINTS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'shop'
  AND CONSTRAINT_NAME IN (
    'chk_shop_geo_pair',
    'chk_shop_geo_longitude',
    'chk_shop_geo_latitude',
    'chk_shop_geo_verified_coordinate'
  );
SQL
)"

if [ "${constraint_count}" != "0" ] && [ "${constraint_count}" != "4" ]; then
  echo "Shop GEO coordinate migration is partially applied (${constraint_count}/4 constraints)." >&2
  exit 1
fi

if [ "${constraint_count}" = "0" ]; then
  invalid_rows="$(
    mysql -u"${MYSQL_USER}" "${MYSQL_DATABASE}" --batch --skip-column-names <<'SQL'
SELECT COUNT(*)
FROM shop
WHERE (x IS NULL) <> (y IS NULL)
   OR (x IS NOT NULL AND (x < -180 OR x > 180))
   OR (y IS NOT NULL AND (y < -85.05112878 OR y > 85.05112878))
   OR (geo_status = 'verified' AND (x IS NULL OR y IS NULL));
SQL
  )"

  if [ "${invalid_rows}" != "0" ]; then
    echo "Shop GEO coordinate migration blocked: ${invalid_rows} invalid rows require repair." >&2
    exit 1
  fi

  mysql -u"${MYSQL_USER}" "${MYSQL_DATABASE}" \
    < /opt/citycopilot/migrations/2026-07-30-shop-geo-coordinate-contract.sql
  echo "Applied the shop GCJ-02 coordinate contract migration."
fi

projection_state="$(
  mysql -u"${MYSQL_USER}" "${MYSQL_DATABASE}" --batch --skip-column-names <<'SQL'
SELECT
  (SELECT COUNT(*)
     FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'shop'
      AND COLUMN_NAME = 'projection_version'),
  (SELECT COUNT(*)
     FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'shop_projection_outbox'),
  (SELECT COUNT(*)
     FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'shop_projection_lease');
SQL
)"

set -- ${projection_state}
projection_column_count="${1:-0}"
projection_outbox_count="${2:-0}"
projection_lease_count="${3:-0}"
projection_total=$((projection_column_count + projection_outbox_count + projection_lease_count))

if [ "${projection_total}" = "0" ]; then
  mysql -u"${MYSQL_USER}" "${MYSQL_DATABASE}" \
    < /opt/citycopilot/migrations/2026-08-04-shop-projection-outbox.sql
  echo "Applied the durable shop projection outbox migration."
elif [ "${projection_total}" != "3" ]; then
  echo "Shop projection migration is partially applied (${projection_total}/3 objects)." >&2
  exit 1
fi

# The barrier seed is idempotent and must also be applied to databases that
# received an earlier revision of the same expand migration.
mysql -u"${MYSQL_USER}" "${MYSQL_DATABASE}" <<'SQL'
INSERT INTO shop_projection_lease(lease_name, fencing_token)
VALUES ('shop-projection-cutover', 0)
ON DUPLICATE KEY UPDATE lease_name=VALUES(lease_name);
SQL

echo "Core schema migrations are up to date."
