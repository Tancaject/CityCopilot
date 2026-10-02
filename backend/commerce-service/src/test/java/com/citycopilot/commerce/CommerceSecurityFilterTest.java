package com.citycopilot.commerce;

import com.citycopilot.commerce.booking.BookingPolicy;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CommerceSecurityFilterTest {
    @Test
    void bindsReceiverOverlapAndAnIndependentBookingSecretFromApplicationConfiguration() throws Exception {
        var sources = new YamlPropertySourceLoader().load("commerce", new ClassPathResource("application.yaml"));
        new ApplicationContextRunner()
                .withUserConfiguration(CommerceSecurityFilter.class, BookingPolicy.class)
                .withInitializer(context -> sources
                        .forEach(source -> context.getEnvironment().getPropertySources().addLast(source)))
                .withPropertyValues("CITYCOPILOT_INTERNAL_COMMERCE_TOKEN=new",
                        "CITYCOPILOT_INTERNAL_COMMERCE_PREVIOUS_TOKEN=old",
                        "CITYCOPILOT_BOOKING_CHECKIN_SECRET=stable-signing-secret")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(BookingPolicy.class).checkinSecret()).isEqualTo("stable-signing-secret");
                    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/commerce/v1/orders/1");
                    request.addHeader("X-CityCopilot-Service-Token", "old");
                    assertThat(context.getBean(CommerceSecurityFilter.class)
                            .authenticate(request, new MockHttpServletResponse())).isTrue();
                });
    }

    @Test
    void acceptsMatchingServiceToken() {
        CommerceSecurityFilter interceptor = new CommerceSecurityFilter("expected-token", "");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/commerce/v1/orders/1");
        request.addHeader("X-CityCopilot-Service-Token", "expected-token");

        assertThat(interceptor.authenticate(request, new MockHttpServletResponse())).isTrue();
    }

    @Test
    void rejectsMissingOrWrongServiceToken() {
        CommerceSecurityFilter interceptor = new CommerceSecurityFilter("expected-token", "");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/commerce/v1/orders/1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.authenticate(request, response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void keepsActuatorHealthAvailableForOrchestration() throws Exception {
        CommerceSecurityFilter interceptor = new CommerceSecurityFilter("expected-token", "");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");

        var chain = new org.springframework.mock.web.MockFilterChain();
        interceptor.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void failsClosedWhenServiceTokenIsNotConfigured() {
        CommerceSecurityFilter interceptor = new CommerceSecurityFilter("", "");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/commerce/v1/orders/1");
        request.addHeader("X-CityCopilot-Service-Token", "anything");

        assertThat(interceptor.authenticate(request, new MockHttpServletResponse())).isFalse();
    }

    @Test
    void acceptsRotationOverlapButRequiresCurrentAndRevokesRemovedPrevious() {
        CommerceSecurityFilter overlap = new CommerceSecurityFilter("new", "old");
        for (String token : java.util.List.of("new", "old")) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/commerce/v1/orders/1");
            request.addHeader("X-CityCopilot-Service-Token", token);
            assertThat(overlap.authenticate(request, new MockHttpServletResponse())).isTrue();
        }
        MockHttpServletRequest old = new MockHttpServletRequest("GET", "/commerce/v1/orders/1");
        old.addHeader("X-CityCopilot-Service-Token", "old");
        assertThat(new CommerceSecurityFilter("new", "").authenticate(old,
                new MockHttpServletResponse())).isFalse();
        assertThat(new CommerceSecurityFilter("", "old").authenticate(old,
                new MockHttpServletResponse())).isFalse();
    }

    @Test
    void protectsTheDispatchTargetBeforeHandlerMappingIncludingContextAndEncodedPaths() throws Exception {
        var filter = new CommerceSecurityFilter("new", "old");
        for (DispatcherType dispatch : DispatcherType.values()) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/com%6derce;v=1/no-handler");
            request.setContextPath("/app");
            request.setDispatcherType(dispatch);
            if (dispatch == DispatcherType.INCLUDE) {
                request.setRequestURI("/app/health");
                request.setAttribute(RequestDispatcher.INCLUDE_REQUEST_URI, "/app/commerce/no-handler");
                request.setAttribute(RequestDispatcher.INCLUDE_CONTEXT_PATH, "/app");
            }
            var response = new MockHttpServletResponse();
            var chain = new org.springframework.mock.web.MockFilterChain();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(chain.getRequest()).isNull();
            request.addHeader("X-CityCopilot-Service-Token", "old");
            filter.doFilter(request, new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).isSameAs(request);
        }
        for (String path : java.util.List.of("/health", "/actuator/health", "/commerce-public")) {
            var request = new MockHttpServletRequest("GET", path);
            var chain = new org.springframework.mock.web.MockFilterChain();
            filter.doFilter(request, new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).isSameAs(request);
        }
        var prefixed = new MockHttpServletRequest("GET", "/app/gateway/commerce/no-handler");
        prefixed.setContextPath("/app");
        prefixed.setServletPath("/gateway");
        prefixed.setHttpServletMapping(new org.springframework.mock.web.MockHttpServletMapping(
                "commerce/no-handler", "/gateway/*", "dispatcherServlet", jakarta.servlet.http.MappingMatch.PATH));
        var response = new MockHttpServletResponse();
        filter.doFilter(prefixed, response, (req, res) -> { throw new AssertionError("must authenticate"); });
        assertThat(response.getStatus()).isEqualTo(401);
    }
}
