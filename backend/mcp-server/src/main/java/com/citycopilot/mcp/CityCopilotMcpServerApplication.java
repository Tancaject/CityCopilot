package com.citycopilot.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.citycopilot.core", "com.citycopilot.agent", "com.citycopilot.mcp"})
@EnableScheduling
public class CityCopilotMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CityCopilotMcpServerApplication.class, args);
    }
}
