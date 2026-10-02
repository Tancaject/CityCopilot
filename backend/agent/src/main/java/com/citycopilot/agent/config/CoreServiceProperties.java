package com.citycopilot.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Core 服务连接与工具目录缓存配置。
 *
 * <p>Agent 只编排业务，不直接成为业务数据权威；远程工具与运维接口均通过这些地址、令牌和超时访问 Core。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "citycopilot.core-service")
public class CoreServiceProperties {
    /** Core 内部 HTTP 接口的根地址。 */
    private String baseUrl = "http://127.0.0.1:8081";
    /** 普通 Agent 工具调用使用的服务令牌。 */
    private String serviceToken;
    /** 兼容旧配置的运维服务令牌。 */
    private String opsServiceToken;
    /** 只读诊断请求使用的最小权限令牌。 */
    private String opsReaderServiceToken;
    /** 经审批后的运维执行请求所用令牌。 */
    private String opsExecutorServiceToken;
    /** 建立 Core HTTP 连接的最长等待时间。 */
    private Duration connectTimeout = Duration.ofSeconds(2);
    /** 等待 Core 响应的最长时间。 */
    private Duration readTimeout = Duration.ofSeconds(4);
    /** 工具目录成功刷新后的新鲜期。 */
    private Duration catalogTtl = Duration.ofSeconds(30);
    /** Core 暂时不可用时，最后一次成功目录仍可使用的最长期限。 */
    private Duration catalogMaxStaleness = Duration.ofMinutes(2);
}
