package com.citycopilot.agent.gateway;

import com.citycopilot.agent.budget.AgentBudget;
import com.citycopilot.agent.budget.AgentBudgetException;
import com.citycopilot.agent.budget.AgentBudgetProperties;
import com.citycopilot.agent.tool.ToolMetadata;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ToolGuardBudgetTest {
    @Test void retriesShareBudgetAcrossWorkerThreadsAndStopBeforeSecondInvocation() throws Exception {
        Resilience4jToolExecutionGuard guard = new Resilience4jToolExecutionGuard();
        AgentBudgetProperties limits = new AgentBudgetProperties(); limits.setToolAttempts(1);
        AgentBudget budget = new AgentBudget(limits); AtomicInteger attempts = new AtomicInteger();
        ToolMetadata metadata = new ToolMetadata("read", "read", List.of(), 60, true, null, null, null, List.of());
        try (AgentBudget.Scope ignored = budget.bind()) {
            AgentBudgetException error = assertThrows(AgentBudgetException.class, () -> guard.execute(metadata, () -> {
                assertSame(budget, AgentBudget.current()); attempts.incrementAndGet();
                throw new ToolExecutionException("unavailable", "unavailable", true);
            }));
            assertEquals("agent_tool_attempts_exceeded", error.code());
            assertEquals(1, attempts.get());
        } finally { guard.shutdown(); }
        assertNull(AgentBudget.current());
    }
}
