package com.citycopilot.core;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@EnableAspectJAutoProxy(exposeProxy = true)
@MapperScan("com.citycopilot.core.mapper")
@SpringBootApplication
public class CityCopilotCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(CityCopilotCoreApplication.class, args);
    }

}
