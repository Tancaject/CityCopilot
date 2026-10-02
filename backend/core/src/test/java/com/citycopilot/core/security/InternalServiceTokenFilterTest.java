package com.citycopilot.core.security;

import com.citycopilot.core.config.InternalApiProperties;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

class InternalServiceTokenFilterTest {

    @Test
    void authenticatesEveryProtectedDispatchTargetAndScopesTheTrustedActor() throws Exception {
        var properties = new InternalApiProperties();
        properties.setServiceTokens(java.util.Map.of("ops-reader", "current"));
        properties.setPreviousServiceTokens(java.util.Map.of("ops-reader", "previous"));
        var filter = new InternalServiceTokenFilter(properties);
        for (DispatcherType dispatch : DispatcherType.values()) {
            var request = new MockHttpServletRequest("GET", "/app/int%65rnal;x=1/no-handler");
            request.setContextPath("/app");
            request.setDispatcherType(dispatch);
            if (dispatch == DispatcherType.INCLUDE) {
                request.setRequestURI("/app/health");
                request.setAttribute(RequestDispatcher.INCLUDE_REQUEST_URI, "/app/internal/no-handler");
                request.setAttribute(RequestDispatcher.INCLUDE_CONTEXT_PATH, "/app");
            }
            var response = new MockHttpServletResponse();
            var chain = new org.springframework.mock.web.MockFilterChain();
            filter.doFilter(request, response, chain);
            assertEquals(401, response.getStatus());
            assertNull(chain.getRequest());
            request.addHeader(InternalServiceTokenFilter.SERVICE_TOKEN_HEADER, "previous");
            request.addHeader(InternalServiceTokenFilter.SERVICE_ACTOR_HEADER, "ops-executor");
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                    assertEquals("ops-reader", req.getAttribute(InternalServiceTokenFilter.AUTHENTICATED_ACTOR_ATTRIBUTE)));
            assertNull(request.getAttribute(InternalServiceTokenFilter.AUTHENTICATED_ACTOR_ATTRIBUTE));
        }
        var request = new MockHttpServletRequest("GET", "/internal-public");
        var chain = new org.springframework.mock.web.MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertEquals(request, chain.getRequest());
        var prefixed = new MockHttpServletRequest("GET", "/app/gateway/internal/probe");
        prefixed.setContextPath("/app");
        prefixed.setServletPath("/gateway");
        prefixed.setHttpServletMapping(new org.springframework.mock.web.MockHttpServletMapping(
                "internal/probe", "/gateway/*", "dispatcherServlet", jakarta.servlet.http.MappingMatch.PATH));
        var rejected = new MockHttpServletResponse();
        filter.doFilter(prefixed, rejected, (req, res) -> { throw new AssertionError("must authenticate"); });
        assertEquals(401, rejected.getStatus());
    }

    @Test
    void acceptsBothGenerationsForTheSameActorUntilPreviousIsRemoved() throws Exception {
        InternalApiProperties properties = new InternalApiProperties();
        properties.setServiceTokens(java.util.Map.of("ops-reader", "new-reader", "ops-executor", "executor"));
        properties.setPreviousServiceTokens(java.util.Map.of("ops-reader", "old-reader"));
        InternalServiceTokenFilter interceptor = new InternalServiceTokenFilter(properties);
        for (String token : java.util.List.of("new-reader", "old-reader")) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(InternalServiceTokenFilter.SERVICE_TOKEN_HEADER, token);
            request.addHeader(InternalServiceTokenFilter.SERVICE_ACTOR_HEADER, "ops-executor");
            assertTrue(interceptor.authenticate(request, new MockHttpServletResponse()));
            assertEquals("ops-reader", request.getAttribute(InternalServiceTokenFilter.AUTHENTICATED_ACTOR_ATTRIBUTE));
        }
        properties.setPreviousServiceTokens(java.util.Map.of());
        MockHttpServletRequest old = new MockHttpServletRequest();
        old.addHeader(InternalServiceTokenFilter.SERVICE_TOKEN_HEADER, "old-reader");
        assertFalse(interceptor.authenticate(old, new MockHttpServletResponse()));
    }

    @Test
    void rejectsCrossActorCollisionsAcrossBothGenerations() throws Exception {
        InternalApiProperties properties = new InternalApiProperties();
        properties.setServiceTokens(java.util.Map.of("ops-reader", "reader", "ops-executor", "executor"));
        InternalServiceTokenFilter interceptor = new InternalServiceTokenFilter(properties);
        for (java.util.Map<String, String> previous : java.util.List.of(
                java.util.Map.of("ops-reader", "executor"),
                java.util.Map.of("ops-reader", "shared-old", "ops-executor", "shared-old"))) {
            properties.setPreviousServiceTokens(previous);
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(InternalServiceTokenFilter.SERVICE_TOKEN_HEADER, previous.get("ops-reader"));
            assertFalse(interceptor.authenticate(request, new MockHttpServletResponse()));
            assertNull(request.getAttribute(InternalServiceTokenFilter.AUTHENTICATED_ACTOR_ATTRIBUTE));
        }
        properties.setPreviousServiceTokens(java.util.Map.of("ops-reader", "reader"));
        MockHttpServletRequest sameActor = new MockHttpServletRequest();
        sameActor.addHeader(InternalServiceTokenFilter.SERVICE_TOKEN_HEADER, "reader");
        assertTrue(interceptor.authenticate(sameActor, new MockHttpServletResponse()));
    }

    @Test
    void previousAloneCannotEnableAnActorOrReactivateLegacyAuthentication() throws Exception {
        InternalApiProperties properties = new InternalApiProperties();
        properties.setServiceToken("legacy");
        properties.setPreviousServiceTokens(java.util.Map.of("ops-reader", "old-reader"));
        InternalServiceTokenFilter interceptor = new InternalServiceTokenFilter(properties);
        for (String token : java.util.List.of("old-reader", "legacy")) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(InternalServiceTokenFilter.SERVICE_TOKEN_HEADER, token);
            assertFalse(interceptor.authenticate(request, new MockHttpServletResponse()));
            assertNull(request.getAttribute(InternalServiceTokenFilter.AUTHENTICATED_ACTOR_ATTRIBUTE));
        }
    }

    @Test
    void rejectsRequestsWhenServiceTokenIsNotConfigured() throws Exception {
        InternalApiProperties properties = new InternalApiProperties();
        InternalServiceTokenFilter interceptor = new InternalServiceTokenFilter(properties);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.authenticate(new MockHttpServletRequest(), response));
        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("INTERNAL_API_DISABLED"));
    }

    @Test
    void requiresTheConfiguredServiceToken() throws Exception {
        InternalApiProperties properties = new InternalApiProperties();
        properties.setServiceToken("shared-secret");
        InternalServiceTokenFilter interceptor = new InternalServiceTokenFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(InternalServiceTokenFilter.SERVICE_TOKEN_HEADER, "wrong-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.authenticate(request, response));
        assertEquals(401, response.getStatus());
    }

    @Test
    void acceptsTheConfiguredServiceToken() throws Exception {
        InternalApiProperties properties = new InternalApiProperties();
        properties.setServiceToken("shared-secret");
        InternalServiceTokenFilter interceptor = new InternalServiceTokenFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(InternalServiceTokenFilter.SERVICE_TOKEN_HEADER, "shared-secret");

        assertTrue(interceptor.authenticate(request, new MockHttpServletResponse()));
        assertEquals(
                "legacy-service",
                request.getAttribute(InternalServiceTokenFilter.AUTHENTICATED_ACTOR_ATTRIBUTE)
        );
    }

    @Test
    void bindsActorToNamedCredentialAndIgnoresSpoofedHeader() throws Exception {
        InternalApiProperties properties = new InternalApiProperties();
        properties.setServiceTokens(java.util.Map.of(
                "agent-service", "agent-secret",
                "ops-agent", "ops-secret"
        ));
        InternalServiceTokenFilter interceptor = new InternalServiceTokenFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(InternalServiceTokenFilter.SERVICE_TOKEN_HEADER, "agent-secret");
        request.addHeader(InternalServiceTokenFilter.SERVICE_ACTOR_HEADER, "ops-agent");

        assertTrue(interceptor.authenticate(request, new MockHttpServletResponse()));
        assertEquals(
                "agent-service",
                request.getAttribute(InternalServiceTokenFilter.AUTHENTICATED_ACTOR_ATTRIBUTE)
        );
    }

    @Test
    void rejectsCredentialSharedByMultipleActors() throws Exception {
        InternalApiProperties properties = new InternalApiProperties();
        properties.setServiceTokens(java.util.Map.of(
                "agent-service", "shared",
                "ops-agent", "shared"
        ));
        InternalServiceTokenFilter interceptor = new InternalServiceTokenFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(InternalServiceTokenFilter.SERVICE_TOKEN_HEADER, "shared");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.authenticate(request, response));
        assertEquals(401, response.getStatus());
        assertNull(request.getAttribute(InternalServiceTokenFilter.AUTHENTICATED_ACTOR_ATTRIBUTE));
    }
}
