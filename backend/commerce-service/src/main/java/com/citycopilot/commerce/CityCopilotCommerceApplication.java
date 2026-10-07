package com.citycopilot.commerce;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Commerce 服务启动入口，装配 HTTP 接口、定时任务以及订单异步处理组件。 */
@EnableScheduling
@SpringBootApplication
public class CityCopilotCommerceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CityCopilotCommerceApplication.class, args);
    }
}
