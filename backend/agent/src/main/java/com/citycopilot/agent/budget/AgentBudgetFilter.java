package com.citycopilot.agent.budget;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/** Start the deadline before session resolution and bound the body before Jackson reads it. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public final class AgentBudgetFilter extends OncePerRequestFilter {
    private final AgentExecutionRuntime runtime;
    private final ObjectMapper mapper;

    public AgentBudgetFilter(AgentExecutionRuntime runtime, ObjectMapper mapper) {
        this.runtime = runtime;
        this.mapper = mapper;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !"POST".equals(request.getMethod())
                || !("/agent/chat".equals(path) || path.startsWith("/agent/confirm/"));
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        AgentBudget budget = runtime.newBudget();
        try (AgentBudget.Scope ignored = budget.bind()) {
            int limit = runtime.limits().getRequestBytes();
            if (request.getContentLengthLong() > limit) budget.reject("agent_message_too_large");
            chain.doFilter(new HttpServletRequestWrapper(request) {
                private ServletInputStream bounded;
                @Override public ServletInputStream getInputStream() throws IOException {
                    if (bounded != null) return bounded;
                    ServletInputStream original = request.getInputStream();
                    bounded = new ServletInputStream() {
                        private int count;
                        @Override public int read() throws IOException {
                            budget.check();
                            int value = original.read();
                            if (value >= 0 && ++count > limit) budget.reject("agent_message_too_large");
                            return value;
                        }
                        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                            budget.check();
                            int received = original.read(bytes, offset, Math.min(length, Math.max(1, limit - count + 1)));
                            if (received > 0 && (count += received) > limit) budget.reject("agent_message_too_large");
                            return received;
                        }
                        @Override public boolean isFinished() { return original.isFinished(); }
                        @Override public boolean isReady() { return original.isReady(); }
                        @Override public void setReadListener(ReadListener listener) { original.setReadListener(listener); }
                    };
                    return bounded;
                }
            }, response);
        } catch (AgentBudgetException error) {
            if (!response.isCommitted()) {
                response.setStatus(AgentBudgetAdvice.status(error).value());
                response.setContentType("application/json;charset=UTF-8");
                mapper.writeValue(response.getOutputStream(), Map.of("code", error.code(), "message", error.getMessage(),
                        "writeOutcomeUnknown", error.writeOutcomeUnknown()));
            }
        } finally {
            if (!budget.submitted()) runtime.release(budget);
        }
    }
}
