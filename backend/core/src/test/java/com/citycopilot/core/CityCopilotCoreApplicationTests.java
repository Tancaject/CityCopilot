package com.citycopilot.core;

import com.citycopilot.core.entity.Shop;
import com.citycopilot.core.service.impl.ShopServiceImpl;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.citycopilot.core.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
@Disabled("Manual Redis/MySQL integration checks; not suitable for default clean verify.")
public class CityCopilotCoreApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

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

}
