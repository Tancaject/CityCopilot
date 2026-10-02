package com.citycopilot.http;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DeadlineHandlerInterceptorTest {
    @Test void nestedBindingOfSameDeadlineDoesNotCancelOuterAndCloseIsIdempotent() {
        var deadline = RequestDeadline.create(Duration.ofSeconds(2), null);
        var cancelled = new java.util.concurrent.atomic.AtomicInteger();
        deadline.onCancel(cancelled::incrementAndGet);
        try (var outer = deadline.bind()) {
            var inner = deadline.bind();
            inner.close(); inner.close();
            deadline.check();
            assertThat(RequestDeadline.current()).isSameAs(deadline);
            assertThat(cancelled.get()).isZero();
        }
        assertThat(cancelled.get()).isEqualTo(1);
        assertThat(RequestDeadline.current()).isNull();
    }

    @Test void distinctNestedOperationRestoresOuterDeadline() {
        try (var outer = RequestDeadline.open(Duration.ofSeconds(2), null)) {
            var previous = RequestDeadline.current();
            try (var inner = RequestDeadline.open(Duration.ofSeconds(5), null)) {
                assertThat(RequestDeadline.current().deadlineEpochMillis()).isLessThanOrEqualTo(previous.deadlineEpochMillis());
            }
            previous.check();
            assertThat(RequestDeadline.current()).isSameAs(previous);
        }
        assertThat(RequestDeadline.current()).isNull();
    }

    @Test void publicHeaderCannotExtendOrPrematurelyExpireLocalBudget() throws Exception {
        var interceptor = new DeadlineHandlerInterceptor(Duration.ofSeconds(1), 1, request -> false);
        var request = new MockHttpServletRequest();
        request.addHeader(DeadlineHttpRequestFactory.DEADLINE_HEADER, "1");
        var response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request, response, this)).isTrue();
        assertThat(RequestDeadline.current().remaining()).isLessThanOrEqualTo(Duration.ofSeconds(1));
        interceptor.afterCompletion(request, response, this, null);
        assertThat(RequestDeadline.current()).isNull();
    }

    @Test void expiredAuthenticatedDeadlineRejectsBeforeHandlerAndReturnsCapacity() throws Exception {
        var interceptor = new DeadlineHandlerInterceptor(Duration.ofSeconds(1), 1, request -> true);
        var expired = new MockHttpServletRequest();
        expired.addHeader(DeadlineHttpRequestFactory.DEADLINE_HEADER, "1");
        var response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(expired, response, this)).isFalse();
        assertThat(response.getStatus()).isEqualTo(504);
        assertThat(RequestDeadline.current()).isNull();
        var next = new MockHttpServletRequest();
        assertThat(interceptor.preHandle(next, new MockHttpServletResponse(), this)).isTrue();
        interceptor.afterCompletion(next, response, this, null);
    }

    @Test void authenticatedBudgetCanOnlyShrinkAndCapacityDoesNotQueue() throws Exception {
        var interceptor = new DeadlineHandlerInterceptor(Duration.ofSeconds(1), 1, request -> true);
        var request = new MockHttpServletRequest();
        request.addHeader(DeadlineHttpRequestFactory.DEADLINE_HEADER, Long.toString(System.currentTimeMillis() + 60_000));
        var response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request, response, this)).isTrue();
        assertThat(RequestDeadline.current().remaining()).isLessThanOrEqualTo(Duration.ofSeconds(1));
        var rejected = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(new MockHttpServletRequest(), rejected, this)).isFalse();
        assertThat(rejected.getStatus()).isEqualTo(503);
        assertThat(rejected.getHeader("Retry-After")).isEqualTo("1");
        interceptor.afterCompletion(request, response, this, null);
        var shorter = new MockHttpServletRequest();
        shorter.addHeader(DeadlineHttpRequestFactory.DEADLINE_HEADER, Long.toString(System.currentTimeMillis() + 300));
        assertThat(interceptor.preHandle(shorter, response, this)).isTrue();
        assertThat(RequestDeadline.current().remaining()).isLessThanOrEqualTo(Duration.ofMillis(300));
        interceptor.afterCompletion(shorter, response, this, null);
    }
}
