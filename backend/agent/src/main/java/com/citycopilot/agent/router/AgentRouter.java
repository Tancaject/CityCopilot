package com.citycopilot.agent.router;

import java.util.List;

public final class AgentRouter {
    private static final List<String> SEQUENCE_MARKERS = List.of("然后", "接着", "最后", "先", "then", "next", "finally", "first");
    private static final List<String> WRITE_MARKERS = List.of(
            "下单",
            "支付",
            "退款",
            "取消订单",
            "发布",
            "给这篇笔记点赞",
            "点赞这篇",
            "关注这个用户",
            "取消关注",
            "取关",
            "批量地理编码",
            "重试失败",
            "place order",
            "pay",
            "refund",
            "cancel order",
            "publish",
            "start batch",
            "retry failed"
    );

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
