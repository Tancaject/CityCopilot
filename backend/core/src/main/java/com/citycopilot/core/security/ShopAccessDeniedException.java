package com.citycopilot.core.security;

public final class ShopAccessDeniedException extends RuntimeException {
    public ShopAccessDeniedException(String message) {
        super(message);
    }
}
