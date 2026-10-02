package com.citycopilot.agent.ops;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 旧版运维 Redis Stream 的定时消费者。
 *
 * <p>每次触发最多处理一条消息；可通过配置关闭，为 ops case v2 worker 让路。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "citycopilot.ops",
        name = "worker-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class OpsAgentWorker {
    /** 提供消费组轮询与任务处理。 */
    private final OpsAgentService opsAgentService;

    public OpsAgentWorker(OpsAgentService opsAgentService) {
        this.opsAgentService = opsAgentService;
    }

    /** 按固定延迟轮询一条旧版运维任务。 */
    @Scheduled(fixedDelayString = "${citycopilot.ops.poll-delay-ms:30000}")
    public void poll() {
        opsAgentService.pollOnce();
    }
}
