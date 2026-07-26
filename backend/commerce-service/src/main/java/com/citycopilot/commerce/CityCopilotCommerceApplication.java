package com.citycopilot.commerce;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class CityCopilotCommerceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CityCopilotCommerceApplication.class, args);
    }
}
