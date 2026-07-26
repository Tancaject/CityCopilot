ALTER TABLE `tb_shop`
  ADD COLUMN `owner_user_id` bigint(20) UNSIGNED NULL COMMENT '所属商户用户id' AFTER `type_id`,
  ADD INDEX `idx_shop_owner_user_id` (`owner_user_id`);

-- Assign existing shops explicitly before granting merchant accounts write access.
-- Example:
-- UPDATE tb_shop SET owner_user_id = 1001 WHERE id IN (1, 2);
