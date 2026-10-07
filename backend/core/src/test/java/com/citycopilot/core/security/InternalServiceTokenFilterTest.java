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
        properties.setServiceTokens(java.util.Map.of("commerce-service", "current"));
        properties.setPreviousServiceTokens(java.util.Map.of("commerce-service", "previous"));
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
            request.addHeader(InternalServiceTokenFilter.SERVICE_ACTOR_HEADER, "batch-service");
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                    assertEquals("commerce-service", req.getAttribute(InternalServiceTokenFilter.AUTHENTICATED_ACTOR_ATTRIBUTE)));
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
        properties.setServiceTokens(java.util.Map.of("commerce-service", "new-reader", "batch-service", "executor"));
        properties.setPreviousServiceTokens(java.util.Map.of("commerce-service", "old-reader"));
        InternalServiceTokenFilter interceptor = new InternalServiceTokenFilter(properties);
        for (String token : java.util.List.of("new-reader", "old-reader")) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(InternalServiceTokenFilter.SERVICE_TOKEN_HEADER, token);
            request.addHeader(InternalServiceTokenFilter.SERVICE_ACTOR_HEADER, "batch-service");
            assertTrue(interceptor.authenticate(request, new MockHttpServletResponse()));
            assertEquals("commerce-service", request.getAttribute(InternalServiceTokenFilter.AUTHENTICATED_ACTOR_ATTRIBUTE));
        }
        properties.setPreviousServiceTokens(java.util.Map.of());
        MockHttpServletRequest old = new MockHttpServletRequest();
        old.addHeader(InternalServiceTokenFilter.SERVICE_TOKEN_HEADER, "old-reader");
        assertFalse(interceptor.authenticate(old, new MockHttpServletResponse()));
    }

    @Test
    void rejectsCrossActorCollisionsAcrossBothGenerations() throws Exception {
        InternalApiProperties properties = new InternalApiProperties();
        properties.setServiceTokens(java.util.Map.of("commerce-service", "reader", "batch-service", "executor"));
        InternalServiceTokenFilter interceptor = new InternalServiceTokenFilter(properties);
        for (java.util.Map<String, String> previous : java.util.List.of(
                java.util.Map.of("commerce-service", "executor"),
                java.util.Map.of("commerce-service", "shared-old", "batch-service", "shared-old"))) {
            properties.setPreviousServiceTokens(previous);
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(InternalServiceTokenFilter.SERVICE_TOKEN_HEADER, previous.get("commerce-service"));
            assertFalse(interceptor.authenticate(request, new MockHttpServletResponse()));
            assertNull(request.getAttribute(InternalServiceTokenFilter.AUTHENTICATED_ACTOR_ATTRIBUTE));
        }
        properties.setPreviousServiceTokens(java.util.Map.of("commerce-service", "reader"));
        MockHttpServletRequest sameActor = new MockHttpServletRequest();
        sameActor.addHeader(InternalServiceTokenFilter.SERVICE_TOKEN_HEADER, "reader");
        assertTrue(interceptor.authenticate(sameActor, new MockHttpServletResponse()));
    }

    @Test
    void previousAloneCannotEnableAnActor() throws Exception {
        InternalApiProperties properties = new InternalApiProperties();
        properties.setPreviousServiceTokens(java.util.Map.of("commerce-service", "old-reader"));
        InternalServiceTokenFilter interceptor = new InternalServiceTokenFilter(properties);
        for (String token : java.util.List.of("old-reader")) {
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
        properties.setServiceTokens(java.util.Map.of("commerce-service", "shared-secret"));
        InternalServiceTokenFilter interceptor = new InternalServiceTokenFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(InternalServiceTokenFilter.SERVICE_TOKEN_HEADER, "wrong-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.authenticate(request, response));
        assertEquals(401, response.getStatus());
    }

    @Test
    void bindsActorToNamedCredentialAndIgnoresSpoofedHeader() throws Exception {
        InternalApiProperties properties = new InternalApiProperties();
        properties.setServiceTokens(java.util.Map.of(
                "commerce-service", "commerce-secret",
                "batch-service", "batch-secret"
        ));
        InternalServiceTokenFilter interceptor = new InternalServiceTokenFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(InternalServiceTokenFilter.SERVICE_TOKEN_HEADER, "commerce-secret");
        request.addHeader(InternalServiceTokenFilter.SERVICE_ACTOR_HEADER, "batch-service");

        assertTrue(interceptor.authenticate(request, new MockHttpServletResponse()));
        assertEquals(
                "commerce-service",
                request.getAttribute(InternalServiceTokenFilter.AUTHENTICATED_ACTOR_ATTRIBUTE)
        );
    }

    @Test
    void rejectsCredentialSharedByMultipleActors() throws Exception {
        InternalApiProperties properties = new InternalApiProperties();
        properties.setServiceTokens(java.util.Map.of(
                "commerce-service", "shared",
                "batch-service", "shared"
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
