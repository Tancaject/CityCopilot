package com.citycopilot.agent.security;

import com.citycopilot.contract.security.ServiceUser;

public record AgentRequestContext(ServiceUser user, String authorization, String requestId) {
    public static AgentRequestContext anonymous(String requestId) {
        return new AgentRequestContext(ServiceUser.anonymous(), null, requestId);
    }
}
