package com.citycopilot.core.event;

public interface DomainEventPublisher {
    String publish(OutboxRecord record) throws Exception;
}
