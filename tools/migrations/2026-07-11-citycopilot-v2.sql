SET FOREIGN_KEY_CHECKS = 0;

RENAME TABLE
  `tb_blog` TO `note`,
  `tb_blog_comments` TO `note_comment`,
  `tb_follow` TO `user_follow`,
  `tb_seckill_voucher` TO `seckill_coupon`,
  `tb_shop` TO `shop`,
  `tb_shop_type` TO `shop_type`,
  `tb_sign` TO `user_checkin`,
  `tb_user` TO `user_account`,
  `tb_user_info` TO `user_profile`,
  `tb_voucher` TO `coupon`,
  `tb_voucher_order` TO `coupon_order`,
  `tb_operation_log` TO `operation_log`,
  `tb_geo_batch_job` TO `geo_batch_job`,
  `tb_geo_batch_job_item` TO `geo_batch_job_item`;

ALTER TABLE `shop`
  MODIFY COLUMN `sold` int(10) UNSIGNED NOT NULL,
  MODIFY COLUMN `comments` int(10) UNSIGNED NOT NULL,
  MODIFY COLUMN `score` int(2) UNSIGNED NOT NULL,
  MODIFY COLUMN `y` double UNSIGNED NOT NULL COMMENT '纬度';

SET FOREIGN_KEY_CHECKS = 1;
