package com.citycopilot.commerce;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 将每个 Voucher 确定性映射到一个 Redis Cluster 逻辑分片，并集中生成同槽业务键。
 *
 * <p>路由代际和物理分片数是部署不变量：所有 API 实例按相同算法定位分片，Worker 则轮询全部
 * 分片 Stream。修改这两个值必须执行显式迁移，不能当作普通配置原地滚动发布。</p>
 */
@Component
final class CommerceRedisKeyspace {
    /** 默认单分片配置保留的兼容键前缀，测试和迁移代码可引用。 */
    static final String STOCK_PREFIX = "commerce:{commerce:0:1:0}:seckill:stock:";
    /** 默认单分片的已占用购买资格用户集合前缀。 */
    static final String BUYER_PREFIX = "commerce:{commerce:0:1:0}:seckill:buyers:";
    /** 默认单分片的 Voucher 本地幂等记录前缀。 */
    static final String IDEMPOTENCY_PREFIX = "commerce:{commerce:0:1:0}:seckill:idempotency:";
    /** 默认单分片的库存维护 fence 前缀，存在时阻止新准入。 */
    static final String FENCE_PREFIX = "commerce:{commerce:0:1:0}:seckill:fence:";
    /** 默认单分片中已准入但尚未终态化的订单集合前缀。 */
    static final String INFLIGHT_PREFIX = "commerce:{commerce:0:1:0}:seckill:inflight:";
    /** 默认单分片的幂等库存恢复标记前缀。 */
    static final String RESTORE_MARKER_PREFIX = "commerce:{commerce:0:1:0}:seckill:restore:";
    /** 默认单分片的幂等准入补偿标记前缀。 */
    static final String COMPENSATION_MARKER_PREFIX = "commerce:{commerce:0:1:0}:seckill:compensation:";
    /** 默认单分片的订单准入 Stream，供兼容测试和迁移代码引用。 */
    static final String STREAM = "commerce:{commerce:0:1:0}:stream:orders";
    /** 所有逻辑分片共享的 Redis Stream 消费组名。 */
    static final String ORDER_CONSUMER_GROUP = "commerce-order-workers";

    /** 允许写入 Redis 键名的路由代际格式，限制长度和字符集。 */
    private static final Pattern SAFE_GENERATION = Pattern.compile("[A-Za-z0-9_-]{1,24}");

    /** 当前部署配置的逻辑物理分片数。 */
    private final int physicalShardCount;
    /** 当前部署拥有的路由代际。 */
    private final String routingGeneration;
    /** 各逻辑分片的订单 Stream 键，按分片号排列。 */
    private final List<String> streams;
    /** Stream 键到分片号的反向映射，用于生成稳定恢复来源 ID。 */
    private final Map<String, Integer> streamShards;

    CommerceRedisKeyspace(
            @Value("${citycopilot.commerce.redis-physical-shard-count:1}") int physicalShardCount,
            @Value("${citycopilot.commerce.redis-routing-generation:v1}") String routingGeneration
    ) {
        if (physicalShardCount < 1 || physicalShardCount > 1024) {
            throw new IllegalArgumentException("Commerce Redis physical shard count must be 1..1024");
        }
        String normalizedGeneration = Objects.requireNonNull(
                routingGeneration,
                "routingGeneration"
        ).trim();
        if (!SAFE_GENERATION.matcher(normalizedGeneration).matches()) {
            throw new IllegalArgumentException(
                    "Invalid Commerce Redis routing generation: " + routingGeneration
            );
        }
        this.physicalShardCount = physicalShardCount;
        this.routingGeneration = normalizedGeneration;
        List<String> configuredStreams = new ArrayList<>(physicalShardCount);
        Map<String, Integer> configuredStreamShards = new LinkedHashMap<>(physicalShardCount);
        for (int shard = 0; shard < physicalShardCount; shard++) {
            String stream = prefix(shard) + "stream:orders";
            configuredStreams.add(stream);
            configuredStreamShards.put(stream, shard);
        }
        this.streams = List.copyOf(configuredStreams);
        this.streamShards = Map.copyOf(configuredStreamShards);
    }

    int physicalShardCount() {
        return physicalShardCount;
    }

    String routingGeneration() {
        return routingGeneration;
    }

    /**
     * 计算 Voucher 所属逻辑分片；相同配置下所有实例结果一致。
     *
     * @param voucherId 正数 Voucher ID
     * @return 从 0 开始的分片号
     */
    int physicalShard(long voucherId) {
        if (voucherId <= 0) {
            throw new IllegalArgumentException("voucherId must be positive");
        }
        return Math.floorMod(Long.hashCode(voucherId), physicalShardCount);
    }

    /** @return Voucher 所属分片的 Redis Cluster hash tag */
    String clusterHashTag(long voucherId) {
        return hashTag(physicalShard(voucherId));
    }

    /** @return 秒杀活动元数据 Hash 键 */
    String campaignMeta(long voucherId) {
        return prefix(voucherId) + "seckill:meta:" + voucherId;
    }

    /** @return 活动元数据修订号键，用于阻止旧事务重新发布旧值 */
    String campaignRevision(long voucherId) {
        return prefix(voucherId) + "seckill:meta-revision:" + voucherId;
    }

    /** @return 活动更新期间关闭准入的临时 fence 键 */
    String campaignUpdateFence(long voucherId) {
        return prefix(voucherId) + "seckill:meta-update-fence:" + voucherId;
    }

    /** @return Redis 可准入库存键 */
    String stock(long voucherId) {
        return prefix(voucherId) + "seckill:stock:" + voucherId;
    }

    /** @return 已占用购买资格的用户集合键 */
    String buyers(long voucherId) {
        return prefix(voucherId) + "seckill:buyers:" + voucherId;
    }

    /**
     * 生成 Voucher 本地幂等记录键；与库存、买家集合和 Stream 同槽，供准入 Lua 原子更新。
     */
    String idempotency(long voucherId, long userId, String idempotencyKey) {
        return prefix(voucherId) + "seckill:idempotency:"
                + voucherId + ":" + userId + ":" + idempotencyKey;
    }

    /**
     * 生成跨 Voucher 的永久幂等身份键。
     *
     * <p>该键有意不放入 Voucher Lua 的 slot：请求先以 SET NX 保留全局身份，崩溃后的重试再用
     * 同一订单 ID 完成 Voucher 本地准入，从而阻止一个幂等键被不同 Voucher 复用。</p>
     */
    String globalIdempotency(long userId, String idempotencyKey) {
        int shard = Math.floorMod(
                31 * Long.hashCode(userId) + idempotencyKey.hashCode(),
                physicalShardCount
        );
        return "commerce:{commerce-idempotency:" + routingGeneration + ":"
                + physicalShardCount + ":" + shard + "}:"
                + userId + ":" + idempotencyKey;
    }

    /** @return 库存维护 fence 键，存在时 Lua 拒绝新准入 */
    String fence(long voucherId) {
        return prefix(voucherId) + "seckill:fence:" + voucherId;
    }

    /** @return 已完成 Redis 准入、尚未终态化的订单成员集合键 */
    String inflight(long voucherId) {
        return prefix(voucherId) + "seckill:inflight:" + voucherId;
    }

    /** @return 幂等库存恢复标记键 */
    String restoreMarker(long voucherId, String sourceId) {
        return prefix(voucherId) + "seckill:restore:" + sourceId;
    }

    /** @return 幂等准入补偿标记键 */
    String compensationMarker(long voucherId, String sourceId) {
        return prefix(voucherId) + "seckill:compensation:" + sourceId;
    }

    /** @return Voucher 所属逻辑分片的订单 Stream */
    String stream(long voucherId) {
        return streams.get(physicalShard(voucherId));
    }

    /** @return 只读的全部订单 Stream 列表 */
    List<String> streams() {
        return streams;
    }

    /**
     * 将代际、分片和 Stream 记录 ID 合成为数据库恢复表的稳定来源 ID。
     */
    String recoverySourceId(String stream, String recordId) {
        Integer shard = streamShards.get(stream);
        if (shard == null) {
            throw new IllegalArgumentException("Unknown Commerce shard stream: " + stream);
        }
        return routingGeneration + ":" + physicalShardCount + ":" + shard + ":" + recordId;
    }

    private String prefix(long voucherId) {
        return prefix(physicalShard(voucherId));
    }

    private String prefix(int shard) {
        return "commerce:" + hashTag(shard) + ":";
    }

    /** 用 hash tag 固定同一分片的业务键，保证多键 Lua 满足 Redis Cluster 同槽要求。 */
    private String hashTag(int shard) {
        return "{commerce:" + routingGeneration + ":" + physicalShardCount + ":" + shard + "}";
    }

}
