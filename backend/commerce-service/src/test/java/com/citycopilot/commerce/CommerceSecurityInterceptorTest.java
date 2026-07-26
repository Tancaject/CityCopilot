package com.citycopilot.commerce;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CommerceSecurityInterceptorTest {
    @Test
    void acceptsMatchingServiceToken() {
        CommerceSecurityInterceptor interceptor = new CommerceSecurityInterceptor("expected-token");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/commerce/v1/orders/1");
        request.addHeader("X-CityCopilot-Service-Token", "expected-token");

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    void rejectsMissingOrWrongServiceToken() {
        CommerceSecurityInterceptor interceptor = new CommerceSecurityInterceptor("expected-token");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/commerce/v1/orders/1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void keepsActuatorHealthAvailableForOrchestration() {
        CommerceSecurityInterceptor interceptor = new CommerceSecurityInterceptor("expected-token");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    void failsClosedWhenServiceTokenIsNotConfigured() {
        CommerceSecurityInterceptor interceptor = new CommerceSecurityInterceptor("");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/commerce/v1/orders/1");
        request.addHeader("X-CityCopilot-Service-Token", "anything");

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isFalse();
    }
}
