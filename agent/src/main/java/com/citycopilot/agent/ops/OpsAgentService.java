package com.citycopilot.agent.ops;

import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OpsAgentService {
    public static final String STREAM_KEY = "agent.ops.tasks";
    private static final String GROUP = "citycopilot-ops";
    private static final String CONSUMER = "worker";

    private final StringRedisTemplate redisTemplate;

    public OpsAgentService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Map<String, Object> enqueue(String type, Map<String, Object> payload) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("payload", payload == null ? Map.of() : payload);
        body.put("createdAt", Instant.now().toString());
        try {
            Object recordId = redisTemplate.opsForStream().add(STREAM_KEY, body);
            return Map.of("queued", true, "recordId", String.valueOf(recordId), "type", type);
        } catch (Exception error) {
            return Map.of("queued", false, "type", type, "error", error.getMessage());
        }
    }

    public Map<String, Object> pollOnce() {
        try {
            StreamOperations<String, Object, Object> streamOps = redisTemplate.opsForStream();
            if (!Boolean.TRUE.equals(redisTemplate.hasKey(STREAM_KEY))) {
                return Map.of("processed", false, "reason", "empty");
            }
            ensureConsumerGroup(streamOps);
            List<MapRecord<String, Object, Object>> records = streamOps.read(
                    Consumer.from(GROUP, CONSUMER),
                    StreamReadOptions.empty().count(1).block(Duration.ofMillis(250)),
                    StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
            );
            if (records == null || records.isEmpty()) {
                return Map.of("processed", false, "reason", "empty");
            }
            MapRecord<String, Object, Object> record = records.get(0);
            Map<Object, Object> value = record.getValue();
            Map<String, Object> result = process(new OpsTaskRequest(
                    String.valueOf(value.get("type")),
                    Map.of("raw", value.get("payload"))
            ));
            if (Boolean.TRUE.equals(result.get("processed"))) {
                streamOps.acknowledge(STREAM_KEY, GROUP, record.getId());
            }
            return result;
        } catch (Exception error) {
            return Map.of("processed", false, "error", error.getMessage());
        }
    }

    private void ensureConsumerGroup(StreamOperations<String, Object, Object> streamOps) {
        try {
            streamOps.createGroup(STREAM_KEY, ReadOffset.from("0-0"), GROUP);
        } catch (Exception error) {
            String message = error.getMessage();
            if (message == null || !message.contains("BUSYGROUP")) {
                throw error;
            }
        }
    }

    public Map<String, Object> process(OpsTaskRequest task) {
        String type = task.type() == null ? "" : task.type();
        return switch (type) {
            case "geo_calibration" -> Map.of("processed", true, "type", type, "action", "geo_calibration_enqueued");
            case "order_anomaly_scan" -> Map.of("processed", true, "type", type, "action", "order_anomaly_scan_enqueued");
            case "daily_report" -> Map.of("processed", true, "type", type, "action", "daily_report_enqueued");
            default -> Map.of("processed", false, "type", type, "error", "unknown_ops_task");
        };
    }
}
