package com.citycopilot.core.config;

import com.citycopilot.http.DeadlineHttpRequestFactory;
import com.citycopilot.http.RequestDeadline;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.Semaphore;

/** Both Commerce ports consume the same downstream capacity; neither queues waiting callers. */
@Configuration
public class CrossServiceHttpConfig {
    @Bean
    public CommerceHttpFactories commerceHttpFactories(
            @Value("${citycopilot.http.commerce.connect-timeout:2s}") Duration connect,
            @Value("${citycopilot.http.commerce.response-timeout:3s}") Duration commerce,
            @Value("${citycopilot.http.commerce.booking-response-timeout:5s}") Duration booking,
            @Value("${citycopilot.http.commerce.max-concurrent:512}") int maximum) {
        if (maximum < 1) throw new IllegalArgumentException("Commerce HTTP concurrency must be positive");
        Semaphore capacity = new Semaphore(maximum);
        return new CommerceHttpFactories(
                new DeadlineHttpRequestFactory(connect, commerce, RequestDeadline::current, true, capacity),
                new DeadlineHttpRequestFactory(connect, booking, RequestDeadline::current, true, capacity));
    }

    public record CommerceHttpFactories(DeadlineHttpRequestFactory commerce, DeadlineHttpRequestFactory booking) { }
}
