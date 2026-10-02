package com.citycopilot.search;

/** 搜索投影写入、重建或基础设施访问失败时抛出的模块级运行时异常。 */
public final class ShopSearchException extends RuntimeException {

    /**
     * @param message 当前搜索操作的失败说明
     * @param cause Elasticsearch 客户端或投影流程抛出的原始异常
     */
    public ShopSearchException(String message, Throwable cause) {
        super(message, cause);
    }
}
