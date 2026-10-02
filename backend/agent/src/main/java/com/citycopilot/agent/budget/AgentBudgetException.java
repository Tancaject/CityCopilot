package com.citycopilot.agent.budget;

/** A terminal resource decision. It must never enter provider fallback or automatic retry. */
public final class AgentBudgetException extends RuntimeException {
    private final String code;
    private final boolean writeOutcomeUnknown;

    public AgentBudgetException(String code, String message, boolean writeOutcomeUnknown) {
        super(message);
        this.code = code;
        this.writeOutcomeUnknown = writeOutcomeUnknown;
    }

    public String code() { return code; }
    public boolean writeOutcomeUnknown() { return writeOutcomeUnknown; }
}
