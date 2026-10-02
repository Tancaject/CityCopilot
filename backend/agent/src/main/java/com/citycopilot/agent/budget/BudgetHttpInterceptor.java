package com.citycopilot.agent.budget;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Bounds raw response bytes before JSON/SSE allocation and closes active streams on cancellation. */
public final class BudgetHttpInterceptor implements ClientHttpRequestInterceptor {
    public enum ResponseKind { MODEL, TOOL_RESULT, TOOL_CATALOG }

    private final ResponseKind responseKind;
    private final AgentBudgetProperties defaults;

    public BudgetHttpInterceptor(boolean model) { this(model, new AgentBudgetProperties()); }
    public BudgetHttpInterceptor(boolean model, AgentBudgetProperties defaults) {
        this(model ? ResponseKind.MODEL : ResponseKind.TOOL_RESULT, defaults);
    }
    public BudgetHttpInterceptor(ResponseKind responseKind, AgentBudgetProperties defaults) {
        this.responseKind = responseKind;
        this.defaults = defaults;
    }

    @Override public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        AgentBudget budget = AgentBudget.current();
        AgentBudget.checkCurrent();
        AgentBudgetProperties limits = budget == null ? defaults : budget.limits();
        if (body.length > limits.getContextBytes()) reject(budget, "agent_context_too_large");
        ClientHttpResponse response;
        try { response = execution.execute(request, body); }
        catch (IOException | RuntimeException error) { AgentBudget.rethrowIfStopped(error); throw error; }
        AgentBudget.Scope registration = budget == null ? () -> { } : budget.onCancel(response::close);
        // The complete catalog is planning metadata, not one tool's business result.
        int limit = switch (responseKind) {
            case MODEL -> limits.getModelResponseBytes();
            case TOOL_RESULT -> limits.getToolResultBytes();
            case TOOL_CATALOG -> limits.getContextBytes();
        };
        String exceededCode = switch (responseKind) {
            case MODEL -> "agent_model_response_too_large";
            case TOOL_RESULT -> "agent_tool_result_too_large";
            case TOOL_CATALOG -> "agent_tool_catalog_too_large";
        };
        return new ClientHttpResponse() {
            private InputStream bounded;
            @Override public HttpStatusCode getStatusCode() throws IOException { return response.getStatusCode(); }
            @Override public String getStatusText() throws IOException { return response.getStatusText(); }
            @Override public HttpHeaders getHeaders() { return response.getHeaders(); }
            @Override public InputStream getBody() throws IOException {
                if (bounded == null) bounded = new FilterInputStream(response.getBody()) {
                    private int received;
                    private void count(int n) {
                        if (budget != null) budget.check();
                        if (n > 0 && (received += n) > limit) reject(budget, exceededCode);
                    }
                    @Override public int read() throws IOException { if (budget != null) budget.check(); int value = super.read(); count(value < 0 ? 0 : 1); return value; }
                    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                        if (budget != null) budget.check(); int n = in.read(bytes, offset, Math.min(length, Math.max(1, limit - received + 1))); count(n); return n;
                    }
                };
                return bounded;
            }
            @Override public void close() { registration.close(); response.close(); }
        };
    }

    private static void reject(AgentBudget budget, String code) {
        if (budget != null) budget.reject(code);
        throw new AgentBudgetException(code, "Agent HTTP payload exceeded its byte limit", false);
    }
}
