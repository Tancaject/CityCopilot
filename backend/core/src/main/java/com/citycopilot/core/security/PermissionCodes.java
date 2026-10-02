package com.citycopilot.core.security;

/** Core 控制器、工具和角色策略使用的稳定权限码目录。 */
public final class PermissionCodes {
    /** 进入管理端。 */
    public static final String ADMIN_ACCESS = "admin:access";
    /** 读取地理数据。 */
    public static final String GEO_READ = "geo:read";
    /** 修改地理数据。 */
    public static final String GEO_WRITE = "geo:write";
    /** 读取审计记录。 */
    public static final String AUDIT_READ = "audit:read";
    /** 写入审计记录。 */
    public static final String AUDIT_WRITE = "audit:write";
    /** 修改店铺。 */
    public static final String SHOP_WRITE = "shop:write";
    /** 修改优惠券。 */
    public static final String VOUCHER_WRITE = "voucher:write";
    /** 修改订单。 */
    public static final String ORDER_WRITE = "order:write";
    public static final String BOOKING_MANAGE = com.citycopilot.contract.security.PermissionCodes.BOOKING_MANAGE;
    public static final String BOOKING_CHECK_IN = com.citycopilot.contract.security.PermissionCodes.BOOKING_CHECK_IN;
    /** 管理用户。 */
    public static final String USER_WRITE = "user:write";
    /** 管理博客与评论。 */
    public static final String CONTENT_WRITE = "content:write";

    private PermissionCodes() {
    }
}
