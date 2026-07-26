package com.citycopilot.agent.ops.v2;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "citycopilot.ops-v2")
public class OpsV2Properties {
    private boolean enabled = true;
    private String workerId = "local-agent";
    private Duration evidenceTtl = Duration.ofMinutes(2);
    private Duration leaseDuration = Duration.ofSeconds(30);
    private Duration approvalTtl = Duration.ofMinutes(15);
    private int readerThreads = 2;
    private int executorThreads = 1;
}
