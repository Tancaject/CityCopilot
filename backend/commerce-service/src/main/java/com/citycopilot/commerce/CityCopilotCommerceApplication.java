package com.citycopilot.commerce;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Commerce 服务启动入口，装配 HTTP 接口、定时任务以及订单异步处理组件。
 *
 * <p>同一个制品也可运行一次性的路由运维任务；任务完成后需主动关闭 Spring 容器并退出进程。</p>
 */
@EnableScheduling
@SpringBootApplication
public class CityCopilotCommerceApplication {
    /**
     * 启动 Spring Boot；若当前进程执行的是一次性路由任务，则在任务结束后释放资源并退出。
     *
     * @param args 命令行启动参数
     */
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(
                CityCopilotCommerceApplication.class,
                args
        );
        CommerceRoutingOperator operator = context.getBeanProvider(
                CommerceRoutingOperator.class
        ).getIfAvailable();
        if (operator != null && operator.completed()) {
            // 路由运维任务是一次性进程；关闭容器可释放调度器、Hikari 和 Lettuce，随后显式退出，
            // 避免非守护线程让已完成的任务继续驻留。任务异常会直接冒泡并产生非零退出码。
            int exitCode = SpringApplication.exit(context, () -> 0);
            System.exit(exitCode);
        }
    }
}
