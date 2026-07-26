package com.citycopilot.core.tool;

import com.citycopilot.contract.security.PermissionCodes;
import com.citycopilot.contract.tool.ToolDefinition;
import com.citycopilot.contract.tool.ToolInvocationResponse;
import com.citycopilot.core.config.InternalApiProperties;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.UserDTO;
import com.citycopilot.core.security.PermissionPolicy;
import com.citycopilot.core.service.OperationLogService;
import com.citycopilot.core.utils.UserHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

@Service
public class CoreToolInvocationService {
    private static final String IDEMPOTENCY_PREFIX = "internal:tool:idempotency:";
    private static final Set<String> OPS_PERMISSIONS = Set.of(
            PermissionCodes.ADMIN_ACCESS,
            PermissionCodes.AUDIT_READ,
            PermissionCodes.GEO_READ,
            PermissionCodes.GEO_WRITE
    );
    private static final Set<String> OPS_READER_PERMISSIONS = Set.of(
            PermissionCodes.ADMIN_ACCESS,
            PermissionCodes.AUDIT_READ,
            PermissionCodes.GEO_READ
    );
    private static final Set<String> OPS_EXECUTOR_PERMISSIONS = Set.of(
            PermissionCodes.ADMIN_ACCESS,
            PermissionCodes.AUDIT_READ,
            PermissionCodes.ORDER_WRITE
    );

    private final CoreToolRegistry registry;
    private final PermissionPolicy permissionPolicy;
    private final OperationLogService operationLogService;
    private final StringRedisTemplate redisTemplate;
    private final InternalApiProperties properties;
    private final ObjectMapper objectMapper;

    public CoreToolInvocationService(
            CoreToolRegistry registry,
            PermissionPolicy permissionPolicy,
            OperationLogService operationLogService,
            StringRedisTemplate redisTemplate,
            InternalApiProperties properties,
            ObjectMapper objectMapper
    ) {
        this.registry = registry;
        this.permissionPolicy = permissionPolicy;
        this.operationLogService = operationLogService;
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public ToolInvocationResponse invoke(
            String toolName,
            Map<String, Object> arguments,
            String idempotencyKey,
            String serviceActor
    ) {
        CoreToolBinding binding;
        try {
            binding = registry.require(toolName);
        } catch (IllegalArgumentException error) {
            return ToolInvocationResponse.failure("unknown_tool", error.getMessage(), false);
        }

        UserDTO originalUser = UserHolder.getUser();
        boolean servicePrincipalInstalled = false;
        if (originalUser == null && isOpsService(serviceActor)) {
            UserHolder.saveUser(servicePrincipal(serviceActor));
            servicePrincipalInstalled = true;
        }
        try {
            UserDTO actor = UserHolder.getUser();
            if (!hasRequiredPermissions(binding.definition(), actor)) {
                return ToolInvocationResponse.failure(
                        "permission_denied",
                        "Missing permission for tool: " + toolName,
                        false
                );
            }
            return invokeWithIdempotency(binding, arguments, idempotencyKey, actor, serviceActor);
        } finally {
            if (servicePrincipalInstalled) {
                UserHolder.removeUser();
            }
        }
    }

    private ToolInvocationResponse invokeWithIdempotency(
            CoreToolBinding binding,
            Map<String, Object> arguments,
            String idempotencyKey,
            UserDTO actor,
            String serviceActor
    ) {
        if (binding.definition().idempotent()) {
            return execute(binding, arguments);
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return ToolInvocationResponse.failure(
                    "idempotency_key_required",
                    "写操作必须提供 Idempotency-Key",
                    false
            );
        }
        String fingerprint = fingerprint(binding.definition().name(), arguments);
        String actorKey = actor != null && actor.getId() != null
                ? String.valueOf(actor.getId())
                : serviceActor == null ? "anonymous" : serviceActor;
        String redisKey = IDEMPOTENCY_PREFIX + digest(actorKey + ":" + binding.definition().name() + ":" + idempotencyKey);
        try {
            Boolean claimed = redisTemplate.opsForHash().putIfAbsent(redisKey, "fingerprint", fingerprint);
            redisTemplate.expire(redisKey, idempotencyTtl());
            if (!Boolean.TRUE.equals(claimed)) {
                Object existingFingerprint = redisTemplate.opsForHash().get(redisKey, "fingerprint");
                if (!fingerprint.equals(String.valueOf(existingFingerprint))) {
                    return ToolInvocationResponse.failure(
                            "idempotency_conflict",
                            "相同 Idempotency-Key 对应了不同请求",
                            false
                    );
                }
                Object storedResponse = redisTemplate.opsForHash().get(redisKey, "response");
                if (storedResponse != null) {
                    return objectMapper.readValue(String.valueOf(storedResponse), ToolInvocationResponse.class);
                }
                return ToolInvocationResponse.failure(
                        "operation_in_progress",
                        "相同写操作正在执行",
                        true
                );
            }
            ToolInvocationResponse response = execute(binding, arguments);
            redisTemplate.opsForHash().put(redisKey, "response", objectMapper.writeValueAsString(response));
            redisTemplate.expire(redisKey, idempotencyTtl());
            return response;
        } catch (Exception error) {
            return ToolInvocationResponse.failure(
                    "idempotency_store_error",
                    "幂等状态不可用：" + safeMessage(error),
                    true
            );
        }
    }

    private ToolInvocationResponse execute(CoreToolBinding binding, Map<String, Object> arguments) {
        ToolInvocationResponse response;
        try {
            Object[] invocationArguments = invocationArguments(binding, arguments);
            Object data = binding.method().invoke(binding.bean(), invocationArguments);
            response = toToolResponse(data);
        } catch (IllegalArgumentException error) {
            response = ToolInvocationResponse.failure("invalid_args", safeMessage(error), false);
        } catch (IllegalAccessException error) {
            response = ToolInvocationResponse.failure("tool_inaccessible", safeMessage(error), false);
        } catch (InvocationTargetException error) {
            response = ToolInvocationResponse.failure("business_error", safeMessage(error.getTargetException()), false);
        } catch (Exception error) {
            response = ToolInvocationResponse.failure("tool_error", safeMessage(error), false);
        }
        audit(binding.definition().name(), arguments, response);
        return response;
    }

    private ToolInvocationResponse toToolResponse(Object data) {
        if (!(data instanceof Result result)) {
            return ToolInvocationResponse.success(data);
        }
        if (Boolean.TRUE.equals(result.getSuccess())) {
            return ToolInvocationResponse.success(result.getData());
        }
        String code = BusinessErrorPolicy.stableCode(result.getCode());
        return ToolInvocationResponse.failure(
                code,
                result.getErrorMsg() == null ? "Business operation failed" : result.getErrorMsg(),
                BusinessErrorPolicy.retryable(code)
        );
    }

    private Object[] invocationArguments(CoreToolBinding binding, Map<String, Object> arguments) {
        Parameter[] parameters = binding.method().getParameters();
        Object[] values = new Object[parameters.length];
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        for (int index = 0; index < parameters.length; index++) {
            Parameter parameter = parameters[index];
            String positionalName = "arg" + index;
            boolean named = safeArguments.containsKey(parameter.getName());
            boolean positional = safeArguments.containsKey(positionalName);
            if (!named && !positional) {
                boolean required = binding.definition().parameters().get(index).required();
                if (required) {
                    throw new IllegalArgumentException("Missing required argument: " + parameter.getName());
                }
                values[index] = null;
                continue;
            }
            Object raw = named ? safeArguments.get(parameter.getName()) : safeArguments.get(positionalName);
            values[index] = raw == null ? null : objectMapper.convertValue(raw, parameter.getType());
        }
        return values;
    }

    private boolean hasRequiredPermissions(ToolDefinition definition, UserDTO actor) {
        if (definition.requiredPermissions().isEmpty()) {
            return true;
        }
        if (actor == null) {
            return false;
        }
        Set<String> actorPermissions = actor.getPermissions();
        Set<String> permissions = actorPermissions == null || actorPermissions.isEmpty()
                ? permissionPolicy.permissionsForRole(actor.getRole())
                : actorPermissions;
        return definition.requiredPermissions().stream()
                .allMatch(permission -> permissions.contains("*") || permissions.contains(permission));
    }

    private UserDTO servicePrincipal(String serviceActor) {
        UserDTO actor = new UserDTO();
        actor.setNickName(serviceActor);
        actor.setRole("internal_service");
        actor.setPermissions(switch (serviceActor) {
            case "ops-reader" -> OPS_READER_PERMISSIONS;
            case "ops-executor" -> OPS_EXECUTOR_PERMISSIONS;
            default -> OPS_PERMISSIONS;
        });
        return actor;
    }

    private boolean isOpsService(String serviceActor) {
        return "ops-agent".equals(serviceActor)
                || "ops-reader".equals(serviceActor)
                || "ops-executor".equals(serviceActor);
    }

    private void audit(String toolName, Map<String, Object> arguments, ToolInvocationResponse response) {
        operationLogService.record(
                "agent",
                "tool_call",
                "agent_tool",
                null,
                toolName,
                arguments,
                response.ok() ? response.data() : response.error(),
                response.ok(),
                response.error() == null ? null : response.error().message()
        );
    }

    private String fingerprint(String toolName, Map<String, Object> arguments) {
        try {
            return digest(toolName + ":" + objectMapper.writeValueAsString(arguments == null ? Map.of() : arguments));
        } catch (Exception error) {
            throw new IllegalArgumentException("请求参数无法生成幂等指纹", error);
        }
    }

    private String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private Duration idempotencyTtl() {
        Duration configured = properties.getIdempotencyTtl();
        return configured == null || configured.isNegative() || configured.isZero()
                ? Duration.ofHours(24)
                : configured;
    }

    private String safeMessage(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return error == null ? "unknown error" : error.getClass().getSimpleName();
        }
        return error.getMessage();
    }
}
