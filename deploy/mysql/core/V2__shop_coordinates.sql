-- Initial compatibility migration: older volumes may already have all four checks.
CREATE TEMPORARY TABLE core_geo_guard (valid BOOLEAN NOT NULL, CONSTRAINT chk_core_geo_migration_shape CHECK (valid = TRUE));
SET @geo_checks = (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shop' AND CONSTRAINT_TYPE='CHECK'
 AND CONSTRAINT_NAME IN ('chk_shop_geo_pair','chk_shop_geo_longitude','chk_shop_geo_latitude','chk_shop_geo_verified_coordinate'));
INSERT INTO core_geo_guard VALUES (@geo_checks IN (0,4));
INSERT INTO core_geo_guard SELECT COUNT(*) = 0 FROM shop
 WHERE (x IS NULL) <> (y IS NULL) OR (x IS NOT NULL AND (x < -180 OR x > 180))
 OR (y IS NOT NULL AND (y < -85.05112878 OR y > 85.05112878))
 OR (geo_status='verified' AND (x IS NULL OR y IS NULL));
-- Existing coordinates are not transformed: their original coordinate system cannot be proven.
SET @geo_ddl = IF(@geo_checks=0, 'ALTER TABLE `shop`
  MODIFY COLUMN `x` DOUBLE NULL DEFAULT NULL COMMENT ''longitude in GCJ-02'',
  MODIFY COLUMN `y` DOUBLE NULL DEFAULT NULL COMMENT ''latitude in GCJ-02'',
  ADD CONSTRAINT `chk_shop_geo_pair`
    CHECK ((`x` IS NULL AND `y` IS NULL) OR (`x` IS NOT NULL AND `y` IS NOT NULL)),
  ADD CONSTRAINT `chk_shop_geo_longitude`
    CHECK (`x` IS NULL OR (`x` >= -180 AND `x` <= 180)),
  ADD CONSTRAINT `chk_shop_geo_latitude`
    CHECK (`y` IS NULL OR (`y` >= -85.05112878 AND `y` <= 85.05112878)),
  ADD CONSTRAINT `chk_shop_geo_verified_coordinate`
    CHECK (`geo_status` IS NULL OR `geo_status` <> ''verified'' OR (`x` IS NOT NULL AND `y` IS NOT NULL))', 'SELECT 1');
PREPARE core_geo_statement FROM @geo_ddl;
EXECUTE core_geo_statement;
DEALLOCATE PREPARE core_geo_statement;
DROP TEMPORARY TABLE core_geo_guard;
