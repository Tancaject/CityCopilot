package com.citycopilot.core.security;

/** 商户尝试修改非本人名下店铺时抛出的业务授权异常。 */
public final class ShopAccessDeniedException extends RuntimeException {
    /** @param message 可返回给调用方的拒绝原因 */
    public ShopAccessDeniedException(String message) {
        super(message);
    }
}
