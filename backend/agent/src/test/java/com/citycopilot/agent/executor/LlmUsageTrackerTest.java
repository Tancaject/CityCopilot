package com.citycopilot.agent.executor;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LlmUsageTrackerTest {
    @Test void retainsExactProviderUsageAndSeparatesFallbackWithoutLeakingNextRequest() {
        LlmUsageTracker.begin();
        try {
            LlmUsageTracker.response(Map.of("usage", Map.of("prompt_tokens", 120, "completion_tokens", 31)));
            LlmUsageTracker.outcome("test-model", "success");
            LlmUsageTracker.response(Map.of("choices", java.util.List.of()));
            LlmUsageTracker.failure(new org.springframework.web.client.RestClientResponseException("provider rejected", 403, "Forbidden", null,
                    "{\"error\":{\"code\":\"AllocationQuota.FreeTierOnly\",\"message\":\"secret-key-must-not-appear\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.charset.StandardCharsets.UTF_8));
            LlmUsageTracker.outcome("test-model", "error");
            var usage = LlmUsageTracker.snapshot();
            assertEquals(120L, usage.get("inputTokens")); assertEquals(31L, usage.get("outputTokens"));
            assertEquals(1, usage.get("successfulCalls")); assertEquals(1, usage.get("fallbackCalls"));
            assertEquals(Map.of("HTTP_403:AllocationQuota.FreeTierOnly", 1), usage.get("providerErrors"));
            assertFalse(usage.toString().contains("secret-key-must-not-appear"));
            assertTrue(LlmUsageTracker.warning().contains("已返回的业务结果保留"));
        } finally { LlmUsageTracker.clear(); }
        LlmUsageTracker.begin();
        try { assertNull(LlmUsageTracker.snapshot().get("inputTokens")); }
        finally { LlmUsageTracker.clear(); }
    }
}
