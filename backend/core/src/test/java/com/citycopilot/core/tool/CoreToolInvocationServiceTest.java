package com.citycopilot.core.tool;

import com.citycopilot.contract.tool.AgentTool;
import com.citycopilot.contract.tool.ToolInvocationResponse;
import com.citycopilot.core.config.InternalApiProperties;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.UserDTO;
import com.citycopilot.core.security.PermissionPolicy;
import com.citycopilot.core.service.OperationLogService;
import com.citycopilot.core.utils.UserHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoreToolInvocationServiceTest {
    private final Map<String, Map<Object, Object>> redis = new HashMap<>();
    private CoreToolInvocationService service;
    private DemoTools tools;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        tools = new DemoTools();
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBeanDefinitionNames()).thenReturn(new String[]{"demoTools"});
        when(context.getType("demoTools")).thenReturn((Class) DemoTools.class);
        when(context.getBean("demoTools")).thenReturn(tools);
        CoreToolRegistry registry = new CoreToolRegistry(context);
        registry.afterSingletonsInstantiated();

        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hash);
        when(hash.putIfAbsent(anyString(), any(), any())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            Object field = invocation.getArgument(1);
            Object value = invocation.getArgument(2);
            return redis.computeIfAbsent(key, ignored -> new HashMap<>()).putIfAbsent(field, value) == null;
        });
        when(hash.get(anyString(), any())).thenAnswer(invocation ->
                redis.getOrDefault(invocation.getArgument(0), Map.of()).get(invocation.getArgument(1))
        );
        org.mockito.Mockito.doAnswer(invocation -> {
            redis.computeIfAbsent(invocation.getArgument(0), ignored -> new HashMap<>())
                    .put(invocation.getArgument(1), invocation.getArgument(2));
            return null;
        }).when(hash).put(anyString(), any(), any());
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);

        InternalApiProperties properties = new InternalApiProperties();
        properties.setIdempotencyTtl(Duration.ofHours(1));
        service = new CoreToolInvocationService(
                registry,
                new PermissionPolicy(),
                mock(OperationLogService.class),
                redisTemplate,
                properties,
                new ObjectMapper()
        );
        UserDTO user = new UserDTO();
        user.setId(42L);
        user.setPermissions(Set.of("order:write"));
        UserHolder.saveUser(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void writeToolRequiresAnIdempotencyKey() {
        ToolInvocationResponse response = service.invoke("write_demo", Map.of("arg0", "first"), null, null);

        assertFalse(response.ok());
        assertEquals("idempotency_key_required", response.error().code());
        assertEquals(0, tools.calls);
    }

    @Test
    void sameKeyReplaysResponseAndRejectsDifferentFingerprint() {
        ToolInvocationResponse first = service.invoke("write_demo", Map.of("arg0", "first"), "stable-key", null);
        ToolInvocationResponse replay = service.invoke("write_demo", Map.of("arg0", "first"), "stable-key", null);
        ToolInvocationResponse conflict = service.invoke("write_demo", Map.of("arg0", "second"), "stable-key", null);

        assertTrue(first.ok());
        assertTrue(replay.ok());
        assertEquals(first.data(), replay.data());
        assertEquals(1, tools.calls);
        assertFalse(conflict.ok());
        assertEquals("idempotency_conflict", conflict.error().code());
    }

    @Test
    void businessResultFailureIsNotReportedAsToolSuccess() {
        ToolInvocationResponse response = service.invoke("failing_demo", Map.of(), null, null);

        assertFalse(response.ok());
        assertEquals("OUT_OF_STOCK", response.error().code());
        assertEquals("No stock remains", response.error().message());
        assertFalse(response.error().retryable());
    }

    @Test
    void retryableBusinessCodeHasStableToolSemantics() {
        ToolInvocationResponse response = service.invoke("retryable_demo", Map.of(), null, null);

        assertFalse(response.ok());
        assertEquals("TEMPORARY_UNAVAILABLE", response.error().code());
        assertTrue(response.error().retryable());
    }

    static final class DemoTools {
        private int calls;

        @AgentTool(
                name = "write_demo",
                description = "Writes once",
                requiredPermissions = "order:write",
                idempotent = false
        )
        public String write(String value) {
            calls++;
            return value;
        }

        @AgentTool(
                name = "failing_demo",
                description = "Returns a business failure"
        )
        public Result fail() {
            return Result.fail("OUT_OF_STOCK", "No stock remains");
        }

        @AgentTool(name = "retryable_demo", description = "Returns a retryable business failure")
        public Result retryableFailure() {
            return Result.fail("temporary-unavailable", "Try again later");
        }
    }
}
