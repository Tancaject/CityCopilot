package com.citycopilot.core.controller;

import com.citycopilot.contract.security.ServiceUser;
import com.citycopilot.contract.tool.ToolDefinition;
import com.citycopilot.contract.tool.ToolInvocationRequest;
import com.citycopilot.contract.tool.ToolInvocationResponse;
import com.citycopilot.core.dto.UserDTO;
import com.citycopilot.core.security.InternalServiceTokenFilter;
import com.citycopilot.core.security.PermissionPolicy;
import com.citycopilot.core.tool.CoreToolInvocationService;
import com.citycopilot.core.tool.CoreToolRegistry;
import com.citycopilot.core.utils.UserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * Core 内部工具协议控制器。
 *
 * <p>该入口面向受信任的内部服务，不是公开业务 API。请求先由
 * {@link InternalServiceTokenFilter} 校验服务令牌；具体工具的权限、写操作幂等和审计
 * 再由 {@link CoreToolInvocationService} 统一执行。</p>
 */
@RestController
@RequestMapping("/internal/v1")
public class InternalToolController {
    /** 工具元数据及处理方法的注册表。 */
    private final CoreToolRegistry registry;
    /** 工具调用编排器，负责权限、参数绑定、幂等和审计。 */
    private final CoreToolInvocationService invocationService;
    /** 角色到权限集合的解析策略。 */
    private final PermissionPolicy permissionPolicy;

    /**
     * @param registry 工具注册表
     * @param invocationService 工具调用编排器
     * @param permissionPolicy 权限解析策略
     */
    public InternalToolController(
            CoreToolRegistry registry,
            CoreToolInvocationService invocationService,
            PermissionPolicy permissionPolicy
    ) {
        this.registry = registry;
        this.invocationService = invocationService;
        this.permissionPolicy = permissionPolicy;
    }

    /**
     * 返回当前请求携带的终端用户会话视图。
     *
     * <p>没有登录用户时返回匿名会话；旧会话未携带权限集合时，按角色即时补全。</p>
     *
     * @return 可供内部调用方识别用户和权限的会话对象
     */
    @GetMapping("/session")
    public ServiceUser session() {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return ServiceUser.anonymous();
        }
        Set<String> permissions = user.getPermissions();
        if (permissions == null || permissions.isEmpty()) {
            permissions = permissionPolicy.permissionsForRole(user.getRole());
        }
        return new ServiceUser(
                user.getId(),
                user.getNickName(),
                user.getIcon(),
                user.getRole(),
                permissions,
                true
        );
    }

    /**
     * 列出 Core 当前注册的工具契约。
     *
     * @return 稳定工具名、参数和权限等定义；仅描述能力，不执行工具
     */
    @GetMapping("/tools")
    public List<ToolDefinition> tools() {
        return registry.definitions();
    }

    /**
     * 调用指定内部工具。
     *
     * <p>服务身份取自令牌拦截器写入的 request 属性，不能由请求正文伪造。非幂等写工具
     * 必须提供 {@code Idempotency-Key}，相同键由调用服务返回历史结果或稳定冲突。</p>
     *
     * @param toolName 注册表中的稳定工具名
     * @param request 可选调用参数；无正文时按空参数处理
     * @param idempotencyKey 调用方幂等键，写工具必需
     * @param serviceActor 已通过服务令牌认证的内部服务身份
     * @return 工具结果或统一的权限、幂等、参数及执行错误
     */
    @PostMapping("/tools/{toolName}:invoke")
    public ToolInvocationResponse invoke(
            @PathVariable String toolName,
            @RequestBody(required = false) ToolInvocationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestAttribute(
                    value = InternalServiceTokenFilter.AUTHENTICATED_ACTOR_ATTRIBUTE,
                    required = false
            ) String serviceActor
    ) {
        // 将缺失正文正规化为空请求，避免协议适配层把无参数工具误判为空指针错误。
        ToolInvocationRequest safeRequest = request == null
                ? new ToolInvocationRequest(null, null)
                : request;
        // 调用服务在真正反射执行前完成工具存在性、权限和写操作幂等校验。
        return invocationService.invoke(toolName, safeRequest.arguments(), idempotencyKey, serviceActor, safeRequest.expectedVersion());
    }
}
