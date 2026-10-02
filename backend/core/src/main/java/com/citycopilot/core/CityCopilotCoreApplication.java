package com.citycopilot.core;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * Core 服务启动入口。
 *
 * <p>Core 提供用户、店铺、内容、地理和内部 Tool API，是前端及 Agent/MCP 的统一入口；
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
        SpringApplication.run(CityCopilotCoreApplication.class, args);
    }

}
