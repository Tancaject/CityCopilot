package com.citycopilot.agent.memory;

import cn.hutool.crypto.digest.DigestUtil;
import com.citycopilot.contract.security.ServiceUser;

public final class ConversationScope {

    private ConversationScope() {
    }

    public static String sessionKey(ServiceUser user, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String owner = user != null && user.authenticated() && user.id() != null
                ? "user:" + user.id()
                : "anonymous";
        return DigestUtil.sha256Hex(owner + ":" + sessionId);
    }
}
