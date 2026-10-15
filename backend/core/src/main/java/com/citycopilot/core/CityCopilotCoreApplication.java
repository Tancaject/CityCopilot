package com.citycopilot.core;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import java.time.ZoneId;
import java.util.TimeZone;

/**
 * Core 服务启动入口。
 *
 * <p>Core 提供用户、店铺、内容和地理 API，是前端的统一入口；
 * 优惠券和订单写入继续委托给 Commerce 单写者。</p>
 */
@EnableAspectJAutoProxy(exposeProxy = true)
@MapperScan("com.citycopilot.core.mapper")
@SpringBootApplication
public class CityCopilotCoreApplication {

    /**
     * @param args Spring Boot 命令行参数
     */
    public static void main(String[] args) {
        // 业务时区固定为配置值，JDBC 会话时区随 JVM 一致，不受部署主机时区影响。
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(
                System.getenv().getOrDefault("CITYCOPILOT_TIMEZONE", "Asia/Shanghai"))));
        SpringApplication.run(CityCopilotCoreApplication.class, args);
    }

}
