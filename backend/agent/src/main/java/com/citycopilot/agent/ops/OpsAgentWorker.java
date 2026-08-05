package com.citycopilot.agent.ops;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "citycopilot.ops",
        name = "worker-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class OpsAgentWorker {
    private final OpsAgentService opsAgentService;

    public OpsAgentWorker(OpsAgentService opsAgentService) {
        this.opsAgentService = opsAgentService;
    }

    @Scheduled(fixedDelayString = "${citycopilot.ops.poll-delay-ms:30000}")
    public void poll() {
        opsAgentService.pollOnce();
    }
}
