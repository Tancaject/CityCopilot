package com.citycopilot.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.function.Predicate;

/** Admission and deadline scope for synchronous MVC routes, after their authentication interceptor. */
public final class DeadlineHandlerInterceptor implements AsyncHandlerInterceptor {
    private final Duration maximum;
    private final Semaphore capacity;
    private final Predicate<HttpServletRequest> trustInherited;
    private final String scopeAttribute = getClass().getName() + "." + java.util.UUID.randomUUID();

    public DeadlineHandlerInterceptor(Duration maximum, int maxConcurrent, Predicate<HttpServletRequest> trustInherited) {
        if (maxConcurrent < 1) throw new IllegalArgumentException("Request concurrency must be positive");
        if (maximum.isNegative() || maximum.isZero()) throw new IllegalArgumentException("Request timeout must be positive");
        this.maximum = maximum;
        this.capacity = new Semaphore(maxConcurrent);
        this.trustInherited = trustInherited;
    }

    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!capacity.tryAcquire()) {
            response.setHeader("Retry-After", "1");
            reject(response, 503, "REQUEST_CAPACITY_EXHAUSTED", "Service request capacity is exhausted; retry later with the original idempotency key");
            return false;
        }
        RequestDeadline.Scope scope = null;
        try {
            scope = RequestDeadline.open(maximum, trustInherited.test(request)
                    ? request.getHeader(DeadlineHttpRequestFactory.DEADLINE_HEADER) : null);
            RequestDeadline.current().check();
            request.setAttribute(scopeAttribute, scope);
            response.setHeader(DeadlineHttpRequestFactory.DEADLINE_HEADER, Long.toString(RequestDeadline.current().deadlineEpochMillis()));
            return true;
        } catch (IllegalArgumentException | RequestDeadline.DeadlineExceededException error) {
            if (scope != null) scope.close();
            capacity.release();
            boolean expired = error instanceof RequestDeadline.DeadlineExceededException;
            reject(response, expired ? 504 : 400, expired ? "REQUEST_DEADLINE_EXCEEDED" : "INVALID_REQUEST_DEADLINE",
                    expired ? "Request deadline elapsed before this service accepted the operation; query any earlier write result before retrying" : "Invalid internal request deadline header");
            return false;
        }
    }

    @Override public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        finish(request);
    }

    @Override public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // These routes are synchronous. Never retain a servlet ThreadLocal if a route becomes async.
        finish(request);
    }

    private void finish(HttpServletRequest request) {
        Object scope = request.getAttribute(scopeAttribute);
        request.removeAttribute(scopeAttribute);
        if (scope instanceof RequestDeadline.Scope bound) {
            try { bound.close(); } finally { capacity.release(); }
        }
    }

    private static void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
