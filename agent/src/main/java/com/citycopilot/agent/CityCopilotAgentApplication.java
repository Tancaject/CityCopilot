package com.citycopilot.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.hmdp", "com.citycopilot.agent"})
public class CityCopilotAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(CityCopilotAgentApplication.class, args);
    }
}
