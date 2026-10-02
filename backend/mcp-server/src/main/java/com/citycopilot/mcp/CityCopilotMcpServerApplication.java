package com.citycopilot.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** MCP Server 独立进程的 Spring Boot 启动类，负责暴露 CityCopilot 工具协议适配层。 */
@SpringBootApplication
@EnableScheduling
public class CityCopilotMcpServerApplication {

    /**
     * @param args Spring Boot 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(CityCopilotMcpServerApplication.class, args);
    }
}
