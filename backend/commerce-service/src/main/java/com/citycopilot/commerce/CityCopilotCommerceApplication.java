package com.citycopilot.commerce;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.ZoneId;
import java.util.TimeZone;

/** Commerce 服务启动入口，装配 HTTP 接口、定时任务以及订单异步处理组件。 */
@EnableScheduling
@SpringBootApplication
public class CityCopilotCommerceApplication {
    public static void main(String[] args) {
        // 业务时区固定为配置值，JDBC 会话时区随 JVM 一致，不受部署主机时区影响。
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(
                System.getenv().getOrDefault("CITYCOPILOT_TIMEZONE", "Asia/Shanghai"))));
        SpringApplication.run(CityCopilotCommerceApplication.class, args);
    }
}
