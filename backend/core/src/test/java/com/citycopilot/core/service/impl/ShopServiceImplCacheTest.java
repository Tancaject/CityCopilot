package com.citycopilot.core.service.impl;

import com.citycopilot.core.dto.Result;
import com.citycopilot.core.entity.Shop;
import com.citycopilot.core.utils.CacheClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.citycopilot.core.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.citycopilot.core.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.citycopilot.core.utils.RedisConstants.CACHE_SHOP_TTL_JITTER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopServiceImplCacheTest {

    private ShopServiceImpl service;
    private CacheClient cacheClient;

    @BeforeEach
    void setUp() {
        service = new ShopServiceImpl();
        cacheClient = mock(CacheClient.class);
        ReflectionTestUtils.setField(service, "cacheClient", cacheClient);
    }

    @Test
    void shopDetailMainPathUsesMutexProtectedCache() {
        Shop shop = new Shop().setId(21L).setName("protected");
        when(cacheClient.queryWithMutex(
                eq(CACHE_SHOP_KEY),
                eq(21L),
                eq(Shop.class),
                org.mockito.ArgumentMatchers.<Function<Long, Shop>>any(),
                eq(CACHE_SHOP_TTL),
                eq(CACHE_SHOP_TTL_JITTER),
                eq(TimeUnit.MINUTES)
        )).thenReturn(shop);

        Result result = service.queryShopById(21L);

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isSameAs(shop);
        verify(cacheClient).queryWithMutex(
                eq(CACHE_SHOP_KEY),
                eq(21L),
                eq(Shop.class),
                any(),
                eq(CACHE_SHOP_TTL),
                eq(CACHE_SHOP_TTL_JITTER),
                eq(TimeUnit.MINUTES)
        );
    }

    @Test
    void negativeCacheResultKeepsExistingNotFoundContract() {
        when(cacheClient.queryWithMutex(
                eq(CACHE_SHOP_KEY),
                eq(22L),
                eq(Shop.class),
                org.mockito.ArgumentMatchers.<Function<Long, Shop>>any(),
                eq(CACHE_SHOP_TTL),
                eq(CACHE_SHOP_TTL_JITTER),
                eq(TimeUnit.MINUTES)
        )).thenReturn(null);

        Result result = service.queryShopById(22L);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getCode()).isEqualTo("SHOP_NOT_FOUND");
    }
}
