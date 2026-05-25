package com.citycopilot.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.citycopilot.core", "com.citycopilot.agent"})
@EnableScheduling
public class CityCopilotAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(CityCopilotAgentApplication.class, args);
    }
}
