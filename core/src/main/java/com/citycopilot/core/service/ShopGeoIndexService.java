package com.citycopilot.core.service;

import com.citycopilot.core.entity.Shop;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.citycopilot.core.utils.RedisConstants.SHOP_GEO_ALL_KEY;
import static com.citycopilot.core.utils.RedisConstants.SHOP_GEO_KEY;

@Slf4j
@Service
public class ShopGeoIndexService {

    @Resource
    private IShopService shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public int syncShopGeoIndex() {
        List<Shop> shops = shopService.list();
        Set<String> keys = new HashSet<>();
        keys.add(SHOP_GEO_ALL_KEY);
        shops.stream()
                .filter(this::hasCoordinate)
                .map(shop -> SHOP_GEO_KEY + shop.getTypeId())
                .forEach(keys::add);

        stringRedisTemplate.delete(keys);

        int indexed = 0;
        for (Shop shop : shops) {
            if (!hasCoordinate(shop)) {
                continue;
            }
            Point point = new Point(shop.getX(), shop.getY());
            String shopId = shop.getId().toString();
            stringRedisTemplate.opsForGeo().add(SHOP_GEO_ALL_KEY, point, shopId);
            stringRedisTemplate.opsForGeo().add(SHOP_GEO_KEY + shop.getTypeId(), point, shopId);
            indexed++;
        }
        log.info("Synced {} shops into Redis GEO indexes", indexed);
        return indexed;
    }

    private boolean hasCoordinate(Shop shop) {
        return shop.getId() != null
                && shop.getTypeId() != null
                && shop.getX() != null
                && shop.getY() != null
                && "verified".equals(shop.getGeoStatus());
    }
}
