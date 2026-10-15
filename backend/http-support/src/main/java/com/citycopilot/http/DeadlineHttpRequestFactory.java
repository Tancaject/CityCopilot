package com.citycopilot.http;

import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Bounds an entire HTTP exchange, including body reads after the response headers arrive. */
public final class DeadlineHttpRequestFactory implements ClientHttpRequestFactory {
    public static final String DEADLINE_HEADER = "X-CityCopilot-Deadline-Epoch-Ms";
    private static final Set<String> JDK_HEADERS = Set.of("connection", "content-length", "expect", "host", "upgrade");
    private static final ScheduledThreadPoolExecutor TIMEOUTS = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "http-exchange-deadline");
        thread.setDaemon(true);
        return thread;
    });
    static { TIMEOUTS.setRemoveOnCancelPolicy(true); }
    private final HttpClient client;
    private final Duration readTimeout;
    private final Supplier<? extends HttpExecutionBudget> budgets;
    private final boolean propagateDeadline;
    private final Semaphore capacity;

    public DeadlineHttpRequestFactory(Duration connectTimeout, Duration readTimeout,
                                      Supplier<? extends HttpExecutionBudget> budgets,
                                      boolean propagateDeadline, int maxConcurrent) {
        this(connectTimeout, readTimeout, budgets, propagateDeadline, capacity(maxConcurrent));
    }

    /** Callers sharing a downstream service also share its semaphore, even with different hop timeouts. */
    public DeadlineHttpRequestFactory(Duration connectTimeout, Duration readTimeout,
                                      Supplier<? extends HttpExecutionBudget> budgets,
                                      boolean propagateDeadline, Semaphore capacity) {
        if (readTimeout.isNegative() || readTimeout.isZero()) throw new IllegalArgumentException("HTTP response timeout must be positive");
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        this.readTimeout = readTimeout;
        this.budgets = budgets;
        this.propagateDeadline = propagateDeadline;
        this.capacity = capacity;
    }

    private static Semaphore capacity(int maximum) {
        if (maximum < 1) throw new IllegalArgumentException("HTTP concurrency must be positive");
        return new Semaphore(maximum);
    }

    @Override public ClientHttpRequest createRequest(URI uri, HttpMethod method) {
        return new ClientHttpRequest() {
            private final HttpHeaders headers = new HttpHeaders();
            private final Map<String, Object> attributes = new LinkedHashMap<>();
            private final ByteArrayOutputStream body = new ByteArrayOutputStream();
            @Override public HttpMethod getMethod() { return method; }
            @Override public URI getURI() { return uri; }
            @Override public HttpHeaders getHeaders() { return headers; }
            @Override public Map<String, Object> getAttributes() { return attributes; }
            @Override public OutputStream getBody() { return body; }
            @Override public ClientHttpResponse execute() throws IOException {
                // RestClient must keep its transport-error mapping, including unknown write outcomes.
                try { return exchange(uri, method, headers, body.toByteArray()); }
                catch (RuntimeException error) { throw new IOException("HTTP request budget stopped the exchange", error); }
            }
        };
    }

    private ClientHttpResponse exchange(URI uri, HttpMethod method, HttpHeaders headers, byte[] body) throws IOException {
        HttpExecutionBudget budget = budgets.get();
        Duration timeout = readTimeout;
        if (budget != null) {
            Duration remaining = budget.remaining();
            if (remaining.compareTo(timeout) < 0) timeout = remaining;
            // 下游收到同一个逻辑截止时间；每跳重算“墙钟+剩余”会因两种时钟换算漂移。
            if (propagateDeadline) headers.set(DEADLINE_HEADER, Long.toString(budget.deadlineEpochMillis()));
            String requestId = MDC.get(RequestIdFilter.MDC_KEY);
            if (requestId != null) headers.set(RequestIdFilter.HEADER, requestId);
        }
        if (!capacity.tryAcquire()) throw new IOException("HTTP capacity exhausted for " + uri.getAuthority() + "; no request was sent");
        CompletableFuture<HttpResponse<InputStream>> future;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout)
                    .method(method.name(), body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach((name, values) -> {
                if (!JDK_HEADERS.contains(name.toLowerCase(Locale.ROOT))) values.forEach(value -> builder.header(name, value));
            });
            future = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (RuntimeException error) {
            capacity.release();
            throw error;
        }
        AtomicReference<InputStream> active = new AtomicReference<>();
        AtomicBoolean aborted = new AtomicBoolean();
        AtomicBoolean expired = new AtomicBoolean();
        AtomicBoolean finished = new AtomicBoolean();
        Runnable abort = () -> {
            aborted.set(true);
            future.cancel(true);
            close(active.get());
        };
        // A cancellation can race with the headers/body handoff. A late body must also be closed.
        future.whenComplete((response, error) -> {
            if (response != null) {
                active.set(response.body());
                if (aborted.get()) close(response.body());
            }
        });
        HttpExecutionBudget.Cancellation cancellation = budget == null ? () -> { } : budget.onCancel(abort);
        ScheduledFuture<?> timer = TIMEOUTS.schedule(() -> { expired.set(true); abort.run(); }, timeout.toNanos(), TimeUnit.NANOSECONDS);
        Runnable finish = () -> {
            if (finished.compareAndSet(false, true)) {
                timer.cancel(false);
                cancellation.close();
                abort.run();
                capacity.release();
            }
        };
        try {
            HttpResponse<InputStream> response = future.get();
            active.set(response.body());
            check(budget, expired);
            if (aborted.get()) throw new IOException("HTTP exchange cancelled");
            HttpHeaders responseHeaders = new HttpHeaders();
            response.headers().map().forEach(responseHeaders::put);
            InputStream stream = new FilterInputStream(response.body()) {
                @Override public int read() throws IOException {
                    check(budget, expired);
                    try { int value = in.read(); check(budget, expired); return value; }
                    catch (IOException error) { check(budget, expired); throw error; }
                }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    check(budget, expired);
                    try { int count = in.read(bytes, offset, length); check(budget, expired); return count; }
                    catch (IOException error) { check(budget, expired); throw error; }
                }
            };
            return new ClientHttpResponse() {
                @Override public HttpStatusCode getStatusCode() { return HttpStatusCode.valueOf(response.statusCode()); }
                @Override public String getStatusText() { return ""; }
                @Override public HttpHeaders getHeaders() { return responseHeaders; }
                @Override public InputStream getBody() { return stream; }
                @Override public void close() { finish.run(); }
            };
        } catch (InterruptedException error) {
            finish.run();
            Thread.currentThread().interrupt();
            check(budget, expired);
            throw new IOException("HTTP exchange interrupted", error);
        } catch (ExecutionException | java.util.concurrent.CancellationException error) {
            finish.run();
            check(budget, expired);
            throw new IOException("HTTP exchange failed", error);
        } catch (IOException | RuntimeException error) {
            finish.run();
            throw error;
        }
    }

    private static void check(HttpExecutionBudget budget, AtomicBoolean expired) throws IOException {
        if (budget != null) {
            try { budget.check(); }
            catch (RuntimeException error) { throw new IOException("HTTP request budget stopped the exchange", error); }
        }
        if (expired.get()) throw new SocketTimeoutException("HTTP exchange exceeded its full-response timeout");
    }

    private static void close(InputStream stream) {
        if (stream != null) try { stream.close(); } catch (IOException ignored) { }
    }
}
