package com.citycopilot.core.domain.order;

public record OrderProcessingResult(Outcome outcome, String code, String message) {
    public enum Outcome {
        ORDER_CREATED,
        ALREADY_CREATED,
        TERMINAL_REJECT,
        RETRYABLE_FAILURE
    }

    public static OrderProcessingResult created() {
        return new OrderProcessingResult(Outcome.ORDER_CREATED, "ORDER_CREATED", "Order created");
    }

    public static OrderProcessingResult alreadyCreated() {
        return new OrderProcessingResult(Outcome.ALREADY_CREATED, "ALREADY_CREATED", "Order already exists");
    }

    public static OrderProcessingResult terminal(String code, String message) {
        return new OrderProcessingResult(Outcome.TERMINAL_REJECT, code, message);
    }

    public static OrderProcessingResult retryable(String code, String message) {
        return new OrderProcessingResult(Outcome.RETRYABLE_FAILURE, code, message);
    }

    public boolean acknowledgeable() {
        return outcome != Outcome.RETRYABLE_FAILURE;
    }
}
