package com.citycopilot.commerce;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommerceRedisCampaignMetadataTest {

    @Test
    @SuppressWarnings("unchecked")
    void publishesAuthoritativeCampaignWindowAfterDatabaseCommit() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        CommerceRedisKeyspace keyspace = new CommerceRedisKeyspace(4, "g1");
        when(redis.opsForValue()).thenReturn(values);
        when(redis.execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(Object[].class)
        )).thenReturn(1L);
        when(jdbc.queryForList(contains("WHERE v.id=?"), eq(100L))).thenReturn(List.of(Map.of(
                "id", 100L,
                "type", 1,
                "status", 1,
                "voucher_version", 2L,
                "seckill_version", 3L,
                "begin_time", Timestamp.from(Instant.parse("2026-08-04T00:00:00Z")),
                "end_time", Timestamp.from(Instant.parse("2026-08-05T00:00:00Z"))
        )));
        CommerceRedisCampaignMetadata metadata = new CommerceRedisCampaignMetadata(
                jdbc,
                redis,
                keyspace,
                false,
                300_000L
        );

        metadata.refreshFromDatabase(100L);

        verify(redis).execute(
                any(DefaultRedisScript.class),
                eq(List.of(
                        keyspace.campaignRevision(100L),
                        keyspace.campaignMeta(100L),
                        keyspace.campaignUpdateFence(100L)
                )),
                eq("0"), eq("100"), eq("1"), eq("1"),
                eq("1785801600000"), eq("1785888000000"), eq("2:3"),
                eq("RECONCILE")
        );
    }

    @Test
    void invalidationAndRefreshFailureBothLeaveAdmissionClosed() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        CommerceRedisKeyspace keyspace = new CommerceRedisKeyspace(4, "g1");
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(keyspace.campaignRevision(100L))).thenReturn("7");
        when(redis.execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(Object[].class)
        )).thenReturn(7L);
        CommerceRedisCampaignMetadata metadata = new CommerceRedisCampaignMetadata(
                jdbc,
                redis,
                keyspace,
                false,
                300_000L
        );
        when(jdbc.queryForList(contains("WHERE v.id=?"), eq(100L)))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThat(metadata.invalidateBeforeChange(100L)).isEqualTo(7L);
        metadata.refreshAfterCommit(100L);

        verify(redis, org.mockito.Mockito.times(2)).execute(
                any(DefaultRedisScript.class),
                anyList(),
                any(Object[].class)
        );
    }

    @Test
    void admissionScriptUsesRedisServerTime() throws Exception {
        String lua = new ClassPathResource("lua/commerce_seckill.lua")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(lua).contains("redis.call('time')")
                .contains("'hmget', KEYS[1]")
                .contains("redis.call('get', KEYS[8]) ~= ARGV[6]")
                .contains("return 11")
                .contains("beginEpochMillis")
                .contains("endEpochMillis");
    }
}
