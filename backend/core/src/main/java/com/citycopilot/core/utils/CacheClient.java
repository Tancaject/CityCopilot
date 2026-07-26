package com.citycopilot.core.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.citycopilot.core.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.citycopilot.core.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {
    private StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    public void set(String key, Object value, Long time, TimeUnit unit){//
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //设置逻辑过期
        RedisData redisData=new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R>dbFallback,Long time, TimeUnit unit){//解决缓存穿透
        String key=keyPrefix + id;
        // 通过redis查商户缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            // 存在则直接返回
            return JSONUtil.toBean(json, type);
        }
        if (json!=null){
            return null;
        }
        // 不存在则查数据库
        R r = dbFallback.apply(id);
        // 数据库中也不存在，则返回错误
        if (r == null) {
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        // 存在，写入redis并设置超时时间
        this.set(key,r,time,unit);
        return r;
    }

    //逻辑过期是为高并发场景准备的特殊方案，日常开发中互斥锁方案更常用
    public <R,ID>R queryWithLogicalExpire(String keyPrefix,ID id,Class<R>type,Function<ID,R>dbFallback,Long time,TimeUnit unit){//逻辑过期方法解决缓存击穿
        String key = keyPrefix + id;
        //从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            //不存在，直接返回null（逻辑过期方案假设热点数据已预热）
            return null;
        }
        //存在，将json反序列化为RedisData对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((cn.hutool.json.JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期，直接返回信息
            return r;
        }
        //已过期，需要缓存重建
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) {
                redisData = JSONUtil.toBean(json, RedisData.class);
                expireTime = redisData.getExpireTime();
                if (expireTime.isAfter(LocalDateTime.now())) {
                    //缓存已被其他线程重建，释放锁并返回
                    unLock(lockKey);
                    return JSONUtil.toBean((cn.hutool.json.JSONObject) redisData.getData(), type);
                }
            }
            //开启独立线程，异步重建缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    R newR=dbFallback.apply(id);
                    this.setWithLogicalExpire(key,newR,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }
        return r;
    }

    private boolean tryLock(String key){//设置互斥锁
        boolean flag= Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS));
        return flag;
    }
    private void unLock(String key){//释放锁
        stringRedisTemplate.delete(key);
    }
}
