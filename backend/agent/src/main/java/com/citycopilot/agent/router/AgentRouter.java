package com.citycopilot.agent.router;

import java.util.List;

public final class AgentRouter {
    private static final List<String> SEQUENCE_MARKERS = List.of("然后", "接着", "最后", "先", "then", "next", "finally", "first");
    private static final List<String> WRITE_MARKERS = List.of("下单", "支付", "退款", "取消订单", "发布", "place order", "pay", "refund", "cancel order", "publish");

    public Mode classify(String message) {
        if (message == null || message.isBlank()) {
            return Mode.REACT;
        }
        String normalized = message.toLowerCase();
        if (containsAny(normalized, SEQUENCE_MARKERS) || containsAny(normalized, WRITE_MARKERS)) {
            return Mode.PLAN_EXECUTE;
        }
        return Mode.REACT;
    }

    private static boolean containsAny(String message, List<String> markers) {
        for (String marker : markers) {
            if (message.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
