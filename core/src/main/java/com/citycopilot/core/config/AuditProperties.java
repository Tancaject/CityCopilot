package com.citycopilot.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "citycopilot.audit")
public class AuditProperties {
    private int retentionDays = 90;
    private int exportLimit = 1000;
}
