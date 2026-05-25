package com.citycopilot.core.utils;

public interface ILock {//分布式锁接口
    //尝试获取锁
    boolean tryLock(long timeoutSec);

    //释放锁
    void unlock();
}
