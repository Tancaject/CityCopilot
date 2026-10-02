package com.citycopilot.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 绑定 MCP Server 访问 Core 内部 API 所需的地址、凭证和超时配置。 */
@Component
@ConfigurationProperties(prefix = "citycopilot.core-service")
public class McpCoreProperties {
    /** Core 服务基础地址。 */
    private String baseUrl = "http://127.0.0.1:8081";
    /** Core 用于认证 MCP Server 身份的服务令牌。 */
    private String serviceToken;
    /** 与 Core 建立连接的最长等待时间。 */
    private Duration connectTimeout = Duration.ofSeconds(2);
    /** 等待 Core 返回响应的最长时间。 */
    private Duration readTimeout = Duration.ofSeconds(4);
    /** One logical operation shares this deadline across session, catalog and invocation requests. */
    private Duration requestTimeout = Duration.ofSeconds(8);
    private int maxConcurrent = 16;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getServiceToken() {
        return serviceToken;
    }

    public void setServiceToken(String serviceToken) {
        this.serviceToken = serviceToken;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public Duration getRequestTimeout() { return requestTimeout; }

    public void setRequestTimeout(Duration requestTimeout) {
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("MCP request timeout must be positive");
        }
        this.requestTimeout = requestTimeout;
    }

    public int getMaxConcurrent() { return maxConcurrent; }

    public void setMaxConcurrent(int maxConcurrent) {
        if (maxConcurrent <= 0) throw new IllegalArgumentException("MCP concurrency must be positive");
        this.maxConcurrent = maxConcurrent;
    }
}
