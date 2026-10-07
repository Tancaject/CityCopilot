package com.citycopilot.commerce;

import com.citycopilot.contract.commerce.CommerceContracts.VoucherView;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/** 展示缓存和短暂售罄提示；订单库存仍由准入 Lua 裁决。 */
@Component
final class CommerceReadCache implements MessageListener {
    static final String INVENTORY_CHANNEL = "commerce:inventory:restored";
    private final Cache<String, Boolean> soldOut = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterWrite(Duration.ofSeconds(1)).build();
    private final StringRedisTemplate redis;
    private final CommerceRedisKeyspace keyspace;
    private final ObjectMapper json;
    private static final DefaultRedisScript<Long> WRITE_LIST = new DefaultRedisScript<>("""
            if (redis.call('GET', KEYS[1]) or '0') ~= ARGV[1] then return 0 end
            redis.call('SET', KEYS[2], ARGV[2], 'PX', 5000)
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> INVALIDATE_LIST = new DefaultRedisScript<>("""
            local revision = redis.call('INCR', KEYS[1])
            redis.call('DEL', KEYS[2])
            return revision
            """, Long.class);

    CommerceReadCache(StringRedisTemplate redis, CommerceRedisKeyspace keyspace, ObjectMapper json) {
        this.redis = redis;
        this.keyspace = keyspace;
        this.json = json;
    }

    @Bean
    RedisMessageListenerContainer inventoryCacheListener(RedisConnectionFactory connectionFactory) {
        var listener = new RedisMessageListenerContainer();
        listener.setConnectionFactory(connectionFactory);
        listener.addMessageListener(this, new ChannelTopic(INVENTORY_CHANNEL));
        return listener;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        soldOut.invalidate(new String(message.getBody(), StandardCharsets.UTF_8));
    }

    boolean isSoldOut(long voucherId) {
        return soldOut.getIfPresent(keyspace.stock(voucherId)) != null;
    }

    void markSoldOut(long voucherId) {
        soldOut.put(keyspace.stock(voucherId), true);
    }

    List<VoucherView> vouchers(long shopId, Supplier<List<VoucherView>> loader) {
        String key = voucherListKey(shopId);
        String cached = redis.opsForValue().get(key);
        if (cached != null) return List.of(json.readValue(cached, VoucherView[].class));
        String revision = redis.opsForValue().get(key + ":version");
        List<VoucherView> current = loader.get();
        redis.execute(WRITE_LIST, List.of(key + ":version", key), revision == null ? "0" : revision,
                json.writeValueAsString(current));
        return current;
    }

    void invalidateVouchers(long shopId) {
        String key = voucherListKey(shopId);
        redis.execute(INVALIDATE_LIST, List.of(key + ":version", key));
    }

    private String voucherListKey(long shopId) {
        return "commerce:cache:vouchers:{" + shopId + "}";
    }
}
