package com.citycopilot.agent.budget;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class AgentBudgetTest {
    @Test void countsModelReservationsAcrossOperationsAndRejectsWithoutReset() {
        AgentBudgetProperties limits = new AgentBudgetProperties();
        limits.setModelCalls(2);
        AgentBudget budget = new AgentBudget(limits);
        assertEquals(limits.getModelOutputTokens(), budget.model(Map.of("op", "plan")));
        budget.model(Map.of("op", "critic"));
        assertEquals("agent_model_calls_exceeded", assertThrows(AgentBudgetException.class,
                () -> budget.model(Map.of("op", "summary"))).code());
        assertThrows(AgentBudgetException.class, budget::tool);
    }

    @Test void boundsBytesAndEntirePlanBeforeAnyExecution() {
        AgentBudgetProperties limits = new AgentBudgetProperties();
        limits.setMessageBytes(5);
        assertEquals("agent_message_too_large", assertThrows(AgentBudgetException.class,
                () -> new AgentBudget(limits).message("中文")).code());
        assertEquals("agent_plan_too_large", assertThrows(AgentBudgetException.class,
                () -> new AgentBudget(limits).plan(limits.getPlanSteps() + 1)).code());
        limits.setResponseBytes(12);
        AgentBudget response = new AgentBudget(limits);
        response.response("1234");
        response.response("1234");
        assertEquals("agent_response_too_large", assertThrows(AgentBudgetException.class,
                () -> response.response("x")).code());
    }

    @Test void distinguishesInFlightWriteFromCompletedWrite() {
        AgentBudget budget = new AgentBudget(new AgentBudgetProperties());
        budget.writeStarted();
        assertTrue(budget.failure("agent_cancelled").writeOutcomeUnknown());
        budget.writeCompleted();
        AgentBudgetException completed = budget.failure("agent_cancelled");
        assertFalse(completed.writeOutcomeUnknown());
        assertTrue(completed.getMessage().contains("不会撤销"));
    }

    @Test void boundedAdmissionDoesNotRunRejectedOrCancelledQueueEntries() throws Exception {
        AgentBudgetProperties limits = new AgentBudgetProperties();
        limits.setConcurrency(1); limits.setQueueCapacity(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean queuedRan = new AtomicBoolean();
        try (AgentExecutionRuntime runtime = new AgentExecutionRuntime(limits)) {
            var running = runtime.submit(runtime.newBudget(), () -> { started.countDown(); release.await(); return null; });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            AgentBudget queuedBudget = runtime.newBudget();
            var queued = runtime.submit(queuedBudget, () -> { queuedRan.set(true); return null; });
            assertEquals("agent_overloaded", assertThrows(AgentBudgetException.class,
                    () -> runtime.submit(runtime.newBudget(), () -> fail("must not execute"))).code());
            queuedBudget.cancel("agent_cancelled");
            assertTrue(queued.isCancelled());
            release.countDown(); running.get(2, TimeUnit.SECONDS);
            assertFalse(queuedRan.get());
            assertNull(AgentBudget.current());
            assertTrue(runtime.call(() -> !Thread.currentThread().isInterrupted()));
        } finally { release.countDown(); }
    }

    @Test void deadlineInterruptsActiveWorkAndNextRequestHasCleanContext() throws Exception {
        AgentBudgetProperties limits = new AgentBudgetProperties();
        limits.setConcurrency(1); limits.setDeadline(Duration.ofMillis(100));
        CountDownLatch interrupted = new CountDownLatch(1);
        try (AgentExecutionRuntime runtime = new AgentExecutionRuntime(limits)) {
            assertThrows(AgentBudgetException.class, () -> runtime.call(() -> {
                try { new CountDownLatch(1).await(); } catch (InterruptedException error) { interrupted.countDown(); }
                AgentBudget.checkCurrent(); return null;
            }));
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertTrue(runtime.call(() -> AgentBudget.current() != null && !Thread.currentThread().isInterrupted()));
        }
    }
}
