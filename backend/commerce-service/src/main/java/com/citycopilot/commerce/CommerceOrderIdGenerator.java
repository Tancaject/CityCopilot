package com.citycopilot.commerce;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
final class CommerceOrderIdGenerator {
    private static final long BEGIN_TIMESTAMP = 1_767_225_600L;
    private static final int SEQUENCE_BITS = 32;
    private static final long MAX_SEQUENCE = 0xffff_ffffL;
    private static final DateTimeFormatter DAY = DateTimeFormatter
            .ofPattern("yyyyMMdd")
            .withZone(ZoneOffset.UTC);

    private final StringRedisTemplate redis;

    CommerceOrderIdGenerator(StringRedisTemplate redis) {
        this.redis = redis;
    }

    long nextId() {
        return nextId("order");
    }

    long nextVoucherId() {
        return nextId("voucher");
    }

    private long nextId(String namespace) {
        Instant now = Instant.now();
        long timestamp = now.getEpochSecond() - BEGIN_TIMESTAMP;
        String key = "commerce:id:" + namespace + ":" + DAY.format(now);
        Long sequence = redis.opsForValue().increment(key);
        if (sequence == null || sequence <= 0 || sequence > MAX_SEQUENCE) {
            throw new IllegalStateException("Unable to allocate a unique commerce " + namespace + " id");
        }
        if (sequence == 1L) {
            redis.expire(key, Duration.ofDays(2));
        }
        return (timestamp << SEQUENCE_BITS) | sequence;
    }
}
