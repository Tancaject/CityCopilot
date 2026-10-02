package com.citycopilot.core.utils;

/** 缓存互斥重建等待超时或被中断时抛出的可重试业务异常。 */
public class CacheRebuildBusyException extends RuntimeException {

    /** @param message 返回给调用方的短暂繁忙说明 */
    public CacheRebuildBusyException(String message) {
        super(message);
    }
}
