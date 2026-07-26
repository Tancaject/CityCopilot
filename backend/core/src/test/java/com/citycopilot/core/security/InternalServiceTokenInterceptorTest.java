package com.citycopilot.core.security;

import com.citycopilot.core.config.InternalApiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

class InternalServiceTokenInterceptorTest {

    @Test
    void rejectsRequestsWhenServiceTokenIsNotConfigured() throws Exception {
        InternalApiProperties properties = new InternalApiProperties();
        InternalServiceTokenInterceptor interceptor = new InternalServiceTokenInterceptor(properties);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(new MockHttpServletRequest(), response, new Object()));
        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("INTERNAL_API_DISABLED"));
    }

    @Test
    void requiresTheConfiguredServiceToken() throws Exception {
        InternalApiProperties properties = new InternalApiProperties();
        properties.setServiceToken("shared-secret");
        InternalServiceTokenInterceptor interceptor = new InternalServiceTokenInterceptor(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(InternalServiceTokenInterceptor.SERVICE_TOKEN_HEADER, "wrong-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
    }

    @Test
    void acceptsTheConfiguredServiceToken() throws Exception {
        InternalApiProperties properties = new InternalApiProperties();
        properties.setServiceToken("shared-secret");
        InternalServiceTokenInterceptor interceptor = new InternalServiceTokenInterceptor(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(InternalServiceTokenInterceptor.SERVICE_TOKEN_HEADER, "shared-secret");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertEquals(
                "legacy-service",
                request.getAttribute(InternalServiceTokenInterceptor.AUTHENTICATED_ACTOR_ATTRIBUTE)
        );
    }

    @Test
    void bindsActorToNamedCredentialAndIgnoresSpoofedHeader() throws Exception {
        InternalApiProperties properties = new InternalApiProperties();
        properties.setServiceTokens(java.util.Map.of(
                "agent-service", "agent-secret",
                "ops-agent", "ops-secret"
        ));
        InternalServiceTokenInterceptor interceptor = new InternalServiceTokenInterceptor(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(InternalServiceTokenInterceptor.SERVICE_TOKEN_HEADER, "agent-secret");
        request.addHeader(InternalServiceTokenInterceptor.SERVICE_ACTOR_HEADER, "ops-agent");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertEquals(
                "agent-service",
                request.getAttribute(InternalServiceTokenInterceptor.AUTHENTICATED_ACTOR_ATTRIBUTE)
        );
    }

    @Test
    void rejectsCredentialSharedByMultipleActors() throws Exception {
        InternalApiProperties properties = new InternalApiProperties();
        properties.setServiceTokens(java.util.Map.of(
                "agent-service", "shared",
                "ops-agent", "shared"
        ));
        InternalServiceTokenInterceptor interceptor = new InternalServiceTokenInterceptor(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(InternalServiceTokenInterceptor.SERVICE_TOKEN_HEADER, "shared");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
        assertNull(request.getAttribute(InternalServiceTokenInterceptor.AUTHENTICATED_ACTOR_ATTRIBUTE));
    }
}
