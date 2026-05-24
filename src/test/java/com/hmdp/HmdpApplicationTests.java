package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import jakarta.annotation.Resource;
import org.apache.ibatis.executor.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
public class HmdpApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private ExecutorService es= Executors.newFixedThreadPool(500);

    @Test
    void testIdWorker() throws InterruptedException {
        Runnable task=()->{
            for (int i=0;i<100;i++){
                long id=redisIdWorker.nextId("order");
                System.out.println("id="+id);
            }
        };
        for (int i=0;i<300;i++){
            es.submit(task);
        }
        //等待线程池执行完毕
        es.shutdown();
        es.awaitTermination(1, TimeUnit.MINUTES);
    }
    @Test
    void loadShopData() {
        // 1. 查询所有店铺
        List<Shop> shops = shopService.list();
        // 2. 按 typeId 分组
        Map<Long, List<Shop>> map = shops.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 3. 按类型批量写入 Redis GEO
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            String key = SHOP_GEO_KEY + entry.getKey();
            List<Shop> shopList = entry.getValue();
            // 批量添加，一次性写入同一类型的所有店铺
            for (Shop shop : shopList) {
                stringRedisTemplate.opsForGeo().add(key,
                        new Point(shop.getX(), shop.getY()),
                        shop.getId().toString());
            }
        }
    }

    @Test
    void testSaveShopWithLogicalExpire() {
        //预热店铺数据到Redis（逻辑过期）
        Shop shop = shopService.getById(1L);

        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1, shop, 10L, TimeUnit.SECONDS);

    }
}
