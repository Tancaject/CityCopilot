package com.citycopilot.core.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

@Component
public class RedisIdWorker {//全局唯一id生成器
    /*id一共64位
    符号位（1位）：永远是0
    时间戳（31bit）：以秒为单位
    序列号（32bit）：秒内的计数器，支持每秒生成2^32个不同id
     */
    private static final long BEGIN_TIMESTAMP=1767225600L;//开始时间戳
    private static final int COUNT_BITS=32;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String keyPrefix){
        //生成时间戳
        LocalDateTime now=LocalDateTime.now();
        long second=now.toEpochSecond(ZoneOffset.UTC);
        long timestamp=second-BEGIN_TIMESTAMP;//当前时间戳
        //生成序列号
        String date=now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));//获得当天日期
        //自增长
        long count=stringRedisTemplate.opsForValue().increment("icr:"+keyPrefix+":"+date);
        return timestamp<<COUNT_BITS|count;
    }

}
