package com.citycopilot.agent.ops;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
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
