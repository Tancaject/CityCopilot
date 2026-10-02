package com.citycopilot.agent.ops.v2;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Ops Case v2 的开关、租约、证据、审批和线程池配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "citycopilot.ops-v2")
public class OpsV2Properties {
    /** 是否启用 v2 运维闭环。 */
    private boolean enabled = true;
    /** 是否启动定时 case worker。 */
    private boolean workerEnabled = true;
    /** 竞争数据库租约时使用的实例标识。 */
    private String workerId = "local-agent";
    /** 一致性证据可绑定计划的有效期。 */
    private Duration evidenceTtl = Duration.ofMinutes(2);
    /** 单个 worker 持有 case 的租约时长。 */
    private Duration leaseDuration = Duration.ofSeconds(30);
    /** 人工审批决定的有效期。 */
    private Duration approvalTtl = Duration.ofMinutes(15);
    /** 只读证据采集线程数。 */
    private int readerThreads = 2;
    /** 高风险修复执行线程数，默认串行。 */
    private int executorThreads = 1;
}
