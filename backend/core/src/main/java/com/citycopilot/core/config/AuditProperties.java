package com.citycopilot.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 绑定 {@code citycopilot.audit}，控制审计记录保留与单次导出上限。 */
@Data
@Component
@ConfigurationProperties(prefix = "citycopilot.audit")
public class AuditProperties {
    /** 审计记录保留天数。 */
    private int retentionDays = 90;
    /** 单次导出最多包含的记录数。 */
    private int exportLimit = 1000;
}
