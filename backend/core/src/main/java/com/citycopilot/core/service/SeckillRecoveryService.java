package com.citycopilot.core.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.citycopilot.contract.tool.AgentTool;
import com.citycopilot.contract.tool.ToolApprovalPolicy;
import com.citycopilot.contract.tool.ToolAudience;
import com.citycopilot.contract.tool.ToolRiskLevel;
import com.citycopilot.contract.tool.ToolSideEffect;
import com.citycopilot.core.entity.SeckillReservation;
import com.citycopilot.core.entity.VoucherOrder;
import com.citycopilot.core.mapper.SeckillReservationMapper;
import com.citycopilot.core.mapper.VoucherOrderMapper;
import com.citycopilot.core.security.PermissionCodes;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static com.citycopilot.core.utils.RedisConstants.SECKILL_DEAD_LETTER_STREAM;
import static com.citycopilot.core.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.citycopilot.core.utils.RedisConstants.SECKILL_ORDER_STREAM;
import static com.citycopilot.core.utils.RedisConstants.SECKILL_RESERVATION_KEY;
import static com.citycopilot.core.utils.RedisConstants.SECKILL_STOCK_KEY;

@Service
public class SeckillRecoveryService {
    private static final DefaultRedisScript<Long> CONFIRM_SCRIPT = script("lua/confirm_reservation.lua");
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = script("lua/release_reservation.lua");
    private static final String DLQ_REPLAY_KEY = "coupon:order:dlq-replay:";

    private final SeckillReservationMapper reservationMapper;
    private final VoucherOrderMapper orderMapper;
    private final SeckillReservationService reservationService;
    private final StringRedisTemplate redisTemplate;
    private final OperationLogService operationLogService;
    private final AtomicLong recoverableReservations = new AtomicLong();
    private MeterRegistry meterRegistry;

    @Value("${citycopilot.seckill.recovery.reserved-timeout-seconds:300}")
    private long reservedTimeoutSeconds;
    @Value("${citycopilot.commerce.enabled:false}")
    private boolean commerceEnabled;

    public SeckillRecoveryService(
            SeckillReservationMapper reservationMapper,
            VoucherOrderMapper orderMapper,
            SeckillReservationService reservationService,
            StringRedisTemplate redisTemplate,
            OperationLogService operationLogService
    ) {
        this.reservationMapper = reservationMapper;
        this.orderMapper = orderMapper;
        this.reservationService = reservationService;
        this.redisTemplate = redisTemplate;
        this.operationLogService = operationLogService;
    }

    @Autowired(required = false)
    void configureMetrics(MeterRegistry registry) {
        meterRegistry = registry;
        Gauge.builder("citycopilot.seckill.reservation.recoverable", recoverableReservations, AtomicLong::get)
                .description("Reservations currently waiting for crash recovery")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${citycopilot.seckill.recovery.interval-ms:30000}")
    public void recoverReservations() {
        if (commerceEnabled) {
            return;
        }
        List<SeckillReservation> candidates = reservationMapper.selectList(
                new LambdaQueryWrapper<SeckillReservation>()
                        .in(SeckillReservation::getStatus,
                                SeckillReservationService.RESERVED,
                                SeckillReservationService.RELEASE_REQUIRED,
                                SeckillReservationService.ORDER_CREATED)
                        .lt(SeckillReservation::getUpdatedAt,
                                LocalDateTime.now().minusSeconds(Math.max(30, reservedTimeoutSeconds)))
                        .last("LIMIT 100")
        );
        recoverableReservations.set(candidates.size());
        for (SeckillReservation reservation : candidates) {
            recoverOne(reservation);
        }
    }

    @AgentTool(
            name = "list_seckill_dead_letters",
            description = "List recent Redis Stream seckill dead letters for operator diagnosis",
            requiredPermissions = {PermissionCodes.ADMIN_ACCESS},
            audience = ToolAudience.OPS,
            scope = "ops:seckill",
            rateLimitPerMinute = 20
    )
    public List<Map<String, String>> listDeadLetters(Integer limit) {
        int boundedLimit = Math.max(1, Math.min(limit == null ? 20 : limit, 100));
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().reverseRange(
                SECKILL_DEAD_LETTER_STREAM,
                Range.unbounded()
        );
        if (records == null) {
            return List.of();
        }
        return records.stream().limit(boundedLimit).map(this::toStringMap).toList();
    }

    @AgentTool(
            name = "replay_seckill_dead_letter",
            description = "Replay one seckill dead letter after operator approval; replay is idempotent",
            requiredPermissions = {PermissionCodes.ADMIN_ACCESS},
            audience = ToolAudience.OPS,
            sideEffect = ToolSideEffect.IDEMPOTENT_WRITE,
            risk = ToolRiskLevel.HIGH,
            approval = ToolApprovalPolicy.OPS_APPROVAL,
            scope = "ops:seckill",
            rateLimitPerMinute = 10
    )
    public Map<String, Object> replayDeadLetter(String deadLetterRecordId) {
        String replayKey = DLQ_REPLAY_KEY + deadLetterRecordId;
        Boolean claimed = redisTemplate.opsForValue().setIfAbsent(
                replayKey,
                "claimed",
                Duration.ofDays(7)
        );
        if (!Boolean.TRUE.equals(claimed)) {
            return Map.of("status", "already_replayed", "deadLetterRecordId", deadLetterRecordId);
        }
        try {
            List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().range(
                    SECKILL_DEAD_LETTER_STREAM,
                    Range.closed(deadLetterRecordId, deadLetterRecordId)
            );
            if (records == null || records.isEmpty()) {
                redisTemplate.delete(replayKey);
                throw new IllegalArgumentException("Dead-letter record does not exist: " + deadLetterRecordId);
            }
            Map<String, String> payload = toStringMap(records.get(0));
            payload.remove("recordId");
            payload.remove("retryCount");
            payload.remove("error");
            payload.put("replayedFrom", deadLetterRecordId);
            payload.put("replayedAt", LocalDateTime.now().toString());
            RecordId newRecordId = redisTemplate.opsForStream().add(SECKILL_ORDER_STREAM, payload);
            operationLogService.record(
                    "seckill",
                    "dead_letter_replay",
                    "redis_stream_record",
                    null,
                    deadLetterRecordId,
                    records.get(0).getValue(),
                    Map.of("newRecordId", newRecordId == null ? "<unknown>" : newRecordId.getValue()),
                    true,
                    null
            );
            count("citycopilot.seckill.dead_letter.replay", "result", "success");
            return Map.of(
                    "status", "replayed",
                    "deadLetterRecordId", deadLetterRecordId,
                    "newRecordId", newRecordId == null ? "" : newRecordId.getValue()
            );
        } catch (RuntimeException error) {
            redisTemplate.delete(replayKey);
            count("citycopilot.seckill.dead_letter.replay", "result", "failure");
            throw error;
        }
    }

    private void recoverOne(SeckillReservation reservation) {
        VoucherOrder order = orderMapper.selectById(reservation.getOrderId());
        if (order != null) {
            if (!SeckillReservationService.ORDER_CREATED.equals(reservation.getStatus())) {
                reservationService.transition(
                        reservation.getOrderId(),
                        reservation.getStatus(),
                        SeckillReservationService.ORDER_CREATED,
                        null
                );
            }
            ensureHotMirror(reservation, SeckillReservationService.ORDER_CREATED);
            redisTemplate.execute(
                    CONFIRM_SCRIPT,
                    List.of(SECKILL_RESERVATION_KEY + reservation.getOrderId())
            );
            count("citycopilot.seckill.reservation.recovery", "outcome", "confirmed");
            return;
        }
        if (SeckillReservationService.RESERVED.equals(reservation.getStatus())) {
            reservationService.transition(
                    reservation.getOrderId(),
                    SeckillReservationService.RESERVED,
                    SeckillReservationService.RELEASE_REQUIRED,
                    "WORKER_TIMEOUT"
            );
        }
        ensureHotMirror(reservation, SeckillReservationService.RESERVED);
        Long released = redisTemplate.execute(
                RELEASE_SCRIPT,
                List.of(
                        SECKILL_RESERVATION_KEY + reservation.getOrderId(),
                        SECKILL_STOCK_KEY + reservation.getVoucherId(),
                        SECKILL_ORDER_KEY + reservation.getVoucherId()
                ),
                String.valueOf(reservation.getUserId())
        );
        if (released != null && released == 1L) {
            reservationService.transition(
                    reservation.getOrderId(),
                    SeckillReservationService.RELEASE_REQUIRED,
                    SeckillReservationService.RELEASED,
                    reservation.getFailureCode() == null ? "WORKER_TIMEOUT" : reservation.getFailureCode()
            );
            count("citycopilot.seckill.reservation.recovery", "outcome", "released");
        } else {
            count("citycopilot.seckill.reservation.recovery", "outcome", "retry");
        }
    }

    private void ensureHotMirror(SeckillReservation reservation, String status) {
        String key = SECKILL_RESERVATION_KEY + reservation.getOrderId();
        if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            return;
        }
        redisTemplate.opsForHash().putAll(key, Map.of(
                "orderId", String.valueOf(reservation.getOrderId()),
                "voucherId", String.valueOf(reservation.getVoucherId()),
                "userId", String.valueOf(reservation.getUserId()),
                "status", status
        ));
        redisTemplate.expire(key, Duration.ofDays(7));
    }

    private Map<String, String> toStringMap(MapRecord<String, Object, Object> record) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("recordId", record.getId().getValue());
        record.getValue().forEach((key, value) -> result.put(String.valueOf(key), String.valueOf(value)));
        return result;
    }

    private void count(String name, String tagName, String tagValue) {
        if (meterRegistry != null) {
            Counter.builder(name).tag(tagName, tagValue).register(meterRegistry).increment();
        }
    }

    private static DefaultRedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }
}
