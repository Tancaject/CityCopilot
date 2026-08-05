package com.citycopilot.commerce;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class CityCopilotCommerceApplication {
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(
                CityCopilotCommerceApplication.class,
                args
        );
        CommerceRoutingOperator operator = context.getBeanProvider(
                CommerceRoutingOperator.class
        ).getIfAvailable();
        if (operator != null && operator.completed()) {
            // The routing operator is a one-shot operational process. The
            // dedicated main path closes scheduling, Hikari and Lettuce, then
            // explicitly terminates so no non-daemon runtime thread can keep
            // a completed process alive. Runner failures escape main as non-zero.
            int exitCode = SpringApplication.exit(context, () -> 0);
            System.exit(exitCode);
        }
    }
}
