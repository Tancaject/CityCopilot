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
        if (user == null || !user.authenticated() || user.id() == null) {
            return null;
        }
        String owner = "user:" + user.id();
        return DigestUtil.sha256Hex(owner + ":" + sessionId);
    }
}
