package com.citycopilot.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Agent 服务启动入口。
 *
 * <p>负责装配对话编排、远程工具、记忆、确认与运维任务，并启用定时任务调度。</p>
 */
@SpringBootApplication
@EnableScheduling
public class CityCopilotAgentApplication {
    /**
     * 启动独立的 Agent Spring Boot 进程。
     *
     * @param args 命令行启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(CityCopilotAgentApplication.class, args);
    }
}
