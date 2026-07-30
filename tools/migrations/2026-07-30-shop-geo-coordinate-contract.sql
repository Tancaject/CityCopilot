-- CityCopilot Core shop coordinate contract migration.
--
-- Canonical coordinate system: GCJ-02.
-- Run the preflight query first. It must return zero rows before applying the
-- ALTER TABLE statement. Existing coordinate values are deliberately not
-- transformed because their source coordinate system cannot be proven.

SELECT `id`, `x`, `y`, `geo_status`
FROM `shop`
WHERE (`x` IS NULL) <> (`y` IS NULL)
   OR (`x` IS NOT NULL AND (`x` < -180 OR `x` > 180))
   OR (`y` IS NOT NULL AND (`y` < -85.05112878 OR `y` > 85.05112878))
   OR (`geo_status` = 'verified' AND (`x` IS NULL OR `y` IS NULL));

ALTER TABLE `shop`
  MODIFY COLUMN `x` DOUBLE NULL DEFAULT NULL COMMENT 'longitude in GCJ-02',
  MODIFY COLUMN `y` DOUBLE NULL DEFAULT NULL COMMENT 'latitude in GCJ-02',
  ADD CONSTRAINT `chk_shop_geo_pair`
    CHECK ((`x` IS NULL AND `y` IS NULL) OR (`x` IS NOT NULL AND `y` IS NOT NULL)),
  ADD CONSTRAINT `chk_shop_geo_longitude`
    CHECK (`x` IS NULL OR (`x` >= -180 AND `x` <= 180)),
  ADD CONSTRAINT `chk_shop_geo_latitude`
    CHECK (`y` IS NULL OR (`y` >= -85.05112878 AND `y` <= 85.05112878)),
  ADD CONSTRAINT `chk_shop_geo_verified_coordinate`
    CHECK (`geo_status` IS NULL OR `geo_status` <> 'verified' OR (`x` IS NOT NULL AND `y` IS NOT NULL));
