package com.citycopilot.agent.http;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/agent")
public class AgentController {
    private final AgentPingHandler pingHandler = new AgentPingHandler();

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return pingHandler.ping();
    }
}
