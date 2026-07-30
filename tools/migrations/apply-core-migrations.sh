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

if [ "${constraint_count}" = "4" ]; then
  echo "Core schema migrations are up to date."
  exit 0
fi

if [ "${constraint_count}" != "0" ]; then
  echo "Shop GEO coordinate migration is partially applied (${constraint_count}/4 constraints)." >&2
  exit 1
fi

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
