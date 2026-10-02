package com.citycopilot.contract.security;

/**
 * 跨服务共享的权限码目录。
 *
 * <p>Agent、MCP 与 Core 通过这些稳定字符串传递授权要求，避免各模块自行拼写权限名。</p>
 */
public final class PermissionCodes {
    /** 进入管理端的基础权限。 */
    public static final String ADMIN_ACCESS = "admin:access";
    /** 读取地理位置与索引数据。 */
    public static final String GEO_READ = "geo:read";
    /** 修改地理位置与索引数据。 */
    public static final String GEO_WRITE = "geo:write";
    /** 查看审计记录。 */
    public static final String AUDIT_READ = "audit:read";
    /** 写入或补录审计记录。 */
    public static final String AUDIT_WRITE = "audit:write";
    /** 新增或修改店铺。 */
    public static final String SHOP_WRITE = "shop:write";
    /** 新增或修改优惠券。 */
    public static final String VOUCHER_WRITE = "voucher:write";
    /** 执行订单写操作。 */
    public static final String ORDER_WRITE = "order:write";
    public static final String BOOKING_MANAGE = "booking:manage";
    public static final String BOOKING_CHECK_IN = "booking:check-in";
    /** 管理用户资料或状态。 */
    public static final String USER_WRITE = "user:write";
    /** 管理博客、评论等内容。 */
    public static final String CONTENT_WRITE = "content:write";

    private PermissionCodes() {
    }
}
