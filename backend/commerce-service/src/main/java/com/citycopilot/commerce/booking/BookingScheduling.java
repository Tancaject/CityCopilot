package com.citycopilot.commerce.booking;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "citycopilot.booking", name = "worker-enabled", havingValue = "true", matchIfMissing = true)
public class BookingScheduling {
    @Bean("taskScheduler")
    ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }

    @Bean("bookingScheduler")
    ThreadPoolTaskScheduler bookingScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        // Synchronous MQ retries must not delay held-capacity release or reminders.
        return builder.poolSize(2).threadNamePrefix("booking-scheduler-").build();
    }
}
