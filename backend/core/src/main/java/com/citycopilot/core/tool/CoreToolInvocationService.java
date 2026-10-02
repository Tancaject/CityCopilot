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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.JacksonException;
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

/**
 * Core 工具调用执行器，统一完成工具查找、调用身份、权限、参数转换、幂等和审计。
 *
 * <p>实际业务方法由 {@link CoreToolRegistry} 绑定并通过反射调用，所有结果转换成稳定的工具协议响应。</p>
 */
@Service
public class CoreToolInvocationService {

    /** 非幂等工具在 Redis 中保存请求指纹与响应的键前缀。 */
    private static final String IDEMPOTENCY_PREFIX = "internal:tool:idempotency:";

    /** 兼容通用运维服务身份的权限集合。 */
    private static final Set<String> OPS_PERMISSIONS = Set.of(
            PermissionCodes.ADMIN_ACCESS,
            PermissionCodes.AUDIT_READ,
            PermissionCodes.GEO_READ,
            PermissionCodes.GEO_WRITE
    );
    /** 只读运维服务身份的最小权限集合。 */
    private static final Set<String> OPS_READER_PERMISSIONS = Set.of(
            PermissionCodes.ADMIN_ACCESS,
            PermissionCodes.AUDIT_READ,
            PermissionCodes.GEO_READ
    );
    /** 可执行订单修复类工具的运维服务权限集合。 */
    private static final Set<String> OPS_EXECUTOR_PERMISSIONS = Set.of(
            PermissionCodes.ADMIN_ACCESS,
            PermissionCodes.AUDIT_READ,
            PermissionCodes.ORDER_WRITE
    );

    /** 工具目录和反射绑定注册表。 */
    private final CoreToolRegistry registry;

    /** 角色到权限的解析策略。 */
    private final PermissionPolicy permissionPolicy;

    /** 工具调用审计服务。 */
    private final OperationLogService operationLogService;

    /** 非幂等写工具的 Redis 幂等存储。 */
    private final StringRedisTemplate redisTemplate;

    /** 内部 API 配置，提供幂等记录 TTL。 */
    private final InternalApiProperties properties;

    /** 参数类型转换、请求指纹和响应持久化使用的 JSON 映射器。 */
    private final ObjectMapper objectMapper;

    /**
     * @param registry 工具注册表
     * @param permissionPolicy 权限策略
     * @param operationLogService 审计服务
     * @param redisTemplate Redis 客户端
     * @param properties 内部 API 配置
     * @param objectMapper JSON 映射器
     */
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

    /**
     * 执行一个已注册工具。
     *
     * @param toolName 稳定工具名
     * @param arguments 参数名到原始 JSON 值的映射
     * @param idempotencyKey 非幂等工具必需的调用方幂等键
     * @param serviceActor 可信内部网关传入的服务身份名
     * @return 统一工具响应，不向协议层抛出普通业务错误
     */
    public ToolInvocationResponse invoke(
            String toolName,
            Map<String, Object> arguments,
            String idempotencyKey,
            String serviceActor
    ) {
        return invoke(toolName, arguments, idempotencyKey, serviceActor, null);
    }

    public ToolInvocationResponse invoke(String toolName, Map<String, Object> arguments,
                                         String idempotencyKey, String serviceActor, Integer expectedVersion) {
        CoreToolBinding binding;
        try {
            binding = registry.require(toolName);
        } catch (IllegalArgumentException error) {
            return ToolInvocationResponse.failure("unknown_tool", error.getMessage(), false);
        }

        // Legacy clients mean version 1, never "whatever is deployed now". Check before any idempotency claim.
        int reviewedVersion = expectedVersion == null ? 1 : expectedVersion;
        if (reviewedVersion != binding.definition().contractVersion()) {
            return ToolInvocationResponse.failure("tool_contract_mismatch",
                    "工具契约已变化：" + toolName + "。请重新获取并审阅工具契约后调用；本次未执行。", false);
        }

        UserDTO originalUser = UserHolder.getUser();
        boolean servicePrincipalInstalled = false;
        if (originalUser == null && isOpsService(serviceActor)) {
            // 仅白名单运维身份可在没有终端用户时安装最小权限服务主体。
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

    /**
     * 对非幂等工具执行 Redis 请求抢占、冲突检测和响应复用。
     *
     * @param binding 工具绑定
     * @param arguments 调用参数
     * @param idempotencyKey 调用方幂等键
     * @param actor 当前用户或服务主体
     * @param serviceActor 原始内部服务身份名
     * @return 首次执行、历史响应或稳定幂等错误
     */
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
            // HSETNX 抢占首次执行权；请求指纹阻止同一键被复用于不同参数。
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
            // 幂等状态不可用时关闭失败，避免绕过保护直接执行非幂等写操作。
            return ToolInvocationResponse.failure(
                    "idempotency_store_error",
                    "幂等状态不可用：" + safeMessage(error),
                    true
            );
        }
    }

    /**
     * 转换参数并反射调用实际业务方法，随后无论成功失败都写审计日志。
     *
     * @param binding 工具运行时绑定
     * @param arguments 原始参数
     * @return 统一工具响应
     */
    private ToolInvocationResponse execute(CoreToolBinding binding, Map<String, Object> arguments) {
        ToolInvocationResponse response;
        try {
            Object[] invocationArguments = invocationArguments(binding, arguments);
            // Method.invoke 调用绑定的 Spring Bean 方法，业务异常会包在 InvocationTargetException 中。
            Object data = binding.method().invoke(binding.bean(), invocationArguments);
            response = toToolResponse(data);
        } catch (IllegalArgumentException | JacksonException error) {
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

    /**
     * 将普通返回值或 Core {@link Result} 转成工具协议响应。
     *
     * @param data 业务方法返回值
     * @return 成功数据或带稳定错误码、重试标记的失败响应
     */
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

    /**
     * 按反射参数顺序读取命名参数并通过 Jackson 转为声明类型。
     *
     * @param binding 工具绑定
     * @param arguments 原始参数映射；兼容 {@code arg0} 位置参数名
     * @return 可传给反射方法的参数数组
     */
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

    /**
     * 校验调用主体包含工具声明的全部权限。
     *
     * @param definition 工具合同
     * @param actor 当前调用主体
     * @return 是否授权
     */
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

    /**
     * 为受信运维服务构造只存在于当前线程的最小权限主体。
     *
     * @param serviceActor 白名单服务身份名
     * @return 内部服务 UserDTO
     */
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

    /**
     * @param serviceActor 服务身份名
     * @return 是否属于可安装服务主体的白名单
     */
    private boolean isOpsService(String serviceActor) {
        return "ops-agent".equals(serviceActor)
                || "ops-reader".equals(serviceActor)
                || "ops-executor".equals(serviceActor);
    }

    /**
     * 记录工具名、参数和响应摘要，供后台追踪工具副作用。
     *
     * @param toolName 工具名
     * @param arguments 调用参数
     * @param response 工具响应
     */
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

    /**
     * 为工具名和规范 JSON 参数生成请求指纹。
     *
     * @param toolName 工具名
     * @param arguments 调用参数
     * @return SHA-256 十六进制摘要
     */
    private String fingerprint(String toolName, Map<String, Object> arguments) {
        try {
            return digest(toolName + ":" + objectMapper.writeValueAsString(arguments == null ? Map.of() : arguments));
        } catch (Exception error) {
            throw new IllegalArgumentException("请求参数无法生成幂等指纹", error);
        }
    }

    /**
     * 计算 UTF-8 字符串的 SHA-256 摘要。
     *
     * @param value 原始字符串
     * @return 小写十六进制摘要
     */
    private String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    /** @return 有效幂等记录 TTL；配置无效时使用 24 小时 */
    private Duration idempotencyTtl() {
        Duration configured = properties.getIdempotencyTtl();
        return configured == null || configured.isNegative() || configured.isZero()
                ? Duration.ofHours(24)
                : configured;
    }

    /**
     * 提取适合协议返回的异常信息，缺少消息时退化为异常类名。
     *
     * @param error 异常
     * @return 非空错误文本
     */
    private String safeMessage(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return error == null ? "unknown error" : error.getClass().getSimpleName();
        }
        return error.getMessage();
    }
}
