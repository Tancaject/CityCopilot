package com.citycopilot.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CityCopilotMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CityCopilotMcpServerApplication.class, args);
    }
}
