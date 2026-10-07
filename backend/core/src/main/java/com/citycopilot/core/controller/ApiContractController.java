package com.citycopilot.core.controller;

import com.citycopilot.core.dto.Result;
import com.citycopilot.contract.security.PermissionCodes;
import com.citycopilot.core.security.PermissionPolicy;
import com.citycopilot.core.security.RequirePermission;
import jakarta.annotation.Resource;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 后台 API 契约自描述控制器。
 *
 * <p>从 Spring MVC 已注册的映射中生成 Core 端点、权限和标准错误目录，供管理界面或
 * 内部工具查看；所有接口只读，并受审计读权限保护。</p>
 */
@RestController
@RequestMapping("/admin/api")
@RequirePermission(PermissionCodes.AUDIT_READ)
public class ApiContractController {

    /** Spring MVC 运行时路由表，是端点清单的实际来源。 */
    @Resource(name = "requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    /** 有效角色权限配置的解析策略。 */
    @Resource
    private PermissionPolicy permissionPolicy;

    /**
     * 枚举当前进程实际注册的 Core MVC 端点。
     *
     * <p>过滤框架和第三方控制器，并按路径排序以保持输出稳定；条件化未加载的控制器
     * 不会出现在结果中。</p>
     *
     * @return 请求方法、路径、控制器、处理方法及权限元数据列表
     */
    @GetMapping("/endpoints")
    public Result endpoints() {
        List<Map<String, Object>> endpoints = new ArrayList<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            if (!handler.getBeanType().getName().startsWith("com.citycopilot.core.")) {
                return;
            }
            endpoints.add(Map.of(
                    "methods", methods(info),
                    "paths", paths(info),
                    "controller", handler.getBeanType().getSimpleName(),
                    "handler", handler.getMethod().getName(),
                    "permission", permission(handler)
            ));
        });
        endpoints.sort(Comparator.comparing(item -> String.join(",", castList(item.get("paths")))));
        return Result.ok(endpoints);
    }

    /**
     * 返回对外承诺的标准业务错误目录。
     *
     * @return 错误码及面向调用方的语义说明
     */
    @GetMapping("/errors")
    public Result errors() {
        return Result.ok(List.of(
                error("AUTH_REQUIRED", "用户未登录或 token 已失效"),
                error("PERMISSION_DENIED", "当前角色缺少接口所需权限"),
                error("COMMON_ERROR", "兼容旧接口的通用业务错误"),
                error("BAD_REQUEST", "请求参数不合法或格式错误"),
                error("SERVER_ERROR", "服务端未预期异常"),
                error("UPLOAD_INVALID", "上传文件为空、超限、扩展名、Content-Type 或文件头不符合要求"),
                error("SHOP_NOT_FOUND", "店铺不存在"),
                error("SHOP_TYPE_NOT_FOUND", "店铺分类不存在"),
                error("SHOP_GEO_INVALID", "店铺坐标参数不合法"),
                error("USER_NOT_FOUND", "用户不存在"),
                error("USER_PHONE_INVALID", "手机号格式不合法"),
                error("LOGIN_CODE_RATE_LIMITED", "验证码发送过于频繁"),
                error("LOGIN_CODE_INVALID", "验证码错误或已过期"),
                error("SIGN_ALREADY_DONE", "今日已签到"),
                error("BLOG_NOT_FOUND", "笔记不存在"),
                error("BLOG_CREATE_FAILED", "笔记创建失败"),
                error("AMAP_CONFIG_MISSING", "高德 Web 服务 Key 未配置"),
                error("AMAP_UNAVAILABLE", "高德服务暂时不可用"),
                error("AMAP_GEOCODE_FAILED", "高德地址解析失败"),
                error("AMAP_POI_FAILED", "高德 POI 搜索失败"),
                error("AMAP_NO_MATCH", "高德没有返回匹配地址"),
                error("AMAP_RESPONSE_INVALID", "高德响应格式或坐标异常"),
                error("AMAP_POI_NOT_FOUND", "没有找到指定 POI 候选"),
                error("AMAP_CIRCUIT_OPEN", "高德服务熔断中"),
                error("AMAP_RATE_LIMITED", "高德服务调用被限流"),
                error("GEO_BATCH_NOT_FOUND", "地理批量任务不存在"),
                error("GEO_BATCH_STATUS_INVALID", "地理批量任务状态不允许当前操作"),
                error("GEO_BATCH_RETRY_EMPTY", "没有可重试的地理批量失败明细"),
                error("VOUCHER_NOT_FOUND", "优惠券不存在"),
                error("VOUCHER_NOT_AVAILABLE", "优惠券未上架、配置缺失或不可用"),
                error("VOUCHER_NOT_SECKILL", "当前优惠券不是秒杀券"),
                error("SECKILL_STOCK_NOT_ENOUGH", "秒杀券库存不足"),
                error("SECKILL_DUPLICATE_ORDER", "同一用户重复抢购同一张秒杀券"),
                error("SECKILL_NOT_STARTED", "秒杀尚未开始"),
                error("SECKILL_ENDED", "秒杀已结束"),
                error("ORDER_NOT_FOUND", "订单不存在或不属于当前用户"),
                error("ORDER_STATUS_INVALID", "订单状态不允许当前操作"),
                error("ORDER_PAY_TYPE_INVALID", "支付方式不支持"),
                error("ORDER_STATUS_CHANGED", "订单状态已被其他请求修改")
        ));
    }

    /**
     * 返回合并默认值和配置覆盖后的角色权限表。
     *
     * @return 角色到有效权限集合的映射
     */
    @GetMapping("/permissions")
    public Result permissions() {
        return Result.ok(permissionPolicy.effectiveRolePermissions());
    }

    /**
     * 提取路由声明的 HTTP 方法并稳定排序。
     *
     * @param info Spring MVC 路由元数据
     * @return 方法名列表；未限制方法时返回 {@code ALL}
     */
    private List<String> methods(RequestMappingInfo info) {
        Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
        if (methods.isEmpty()) {
            return List.of("ALL");
        }
        return methods.stream().map(RequestMethod::name).sorted().toList();
    }

    /**
     * 兼容新版 PathPattern 与旧版 AntPath 两种 Spring 路由条件。
     *
     * @param info Spring MVC 路由元数据
     * @return 去重并按字典序排列的路径列表
     */
    private List<String> paths(RequestMappingInfo info) {
        Set<String> paths = new TreeSet<>();
        if (info.getPathPatternsCondition() != null) {
            paths.addAll(info.getPathPatternsCondition().getPatternValues());
        } else if (info.getPatternsCondition() != null) {
            paths.addAll(info.getPatternsCondition().getPatterns());
        }
        return new ArrayList<>(paths);
    }

    /**
     * 解析处理方法的有效权限声明。
     *
     * <p>方法级声明优先于类级声明；使用合并注解查询以兼容组合注解和继承元数据。</p>
     *
     * @param handler MVC 处理方法
     * @return 权限编码和 any-of 匹配模式；无声明时返回空权限
     */
    private Map<String, Object> permission(HandlerMethod handler) {
        RequirePermission permission = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), RequirePermission.class);
        if (permission == null) {
            permission = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), RequirePermission.class);
        }
        if (permission == null) {
            return Map.of("permissions", List.of(), "anyOf", false);
        }
        return Map.of("permissions", List.of(permission.value()), "anyOf", permission.anyOf());
    }

    /**
     * 构造一个稳定的错误目录条目。
     *
     * @param code 机器可读错误码
     * @param message 错误语义说明
     * @return 不可变错误条目
     */
    private Map<String, String> error(String code, String message) {
        return Map.of("code", code, "message", message);
    }

    /**
     * 将端点条目中的路径值安全转换为排序所需的字符串列表。
     *
     * @param value 待转换值
     * @return 字符串列表；结构不符时返回空列表
     */
    @SuppressWarnings("unchecked")
    private List<String> castList(Object value) {
        return value instanceof List<?> list ? (List<String>) list : List.of();
    }
}
