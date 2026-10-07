package com.citycopilot.core.security;

import com.citycopilot.contract.security.PermissionCodes;
import com.citycopilot.core.config.InternalApiProperties;
import com.citycopilot.core.config.MvcConfig;
import com.citycopilot.core.config.UploadProperties;
import com.citycopilot.core.utils.RefreshTokenInterceptor;
import com.citycopilot.core.utils.UserContextFilter;
import com.citycopilot.core.utils.UserHolder;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CoreAuthenticationChainTest {
    @Test
    void filtersPrecedeHandlerMappingAndRetainMvcLoginAndMethodPermissions() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.addBeanFactoryPostProcessor(factory ->
                    factory.setConversionService(ApplicationConversionService.getSharedInstance()));
            context.register(Config.class);
            context.refresh();
            var registrations = context.getBeansOfType(FilterRegistrationBean.class).values().stream()
                    .sorted(java.util.Comparator.comparingInt(FilterRegistrationBean::getOrder)).toList();
            assertThat(registrations).hasSize(2);
            assertThat(registrations.get(0).getFilter()).isInstanceOf(InternalServiceTokenFilter.class);
            assertThat(registrations.get(1).getFilter()).isInstanceOf(UserContextFilter.class);
            assertThat(registrations).allSatisfy(registration -> {
                assertThat(registration.isAsyncSupported()).isTrue();
                assertThat(registration.getUrlPatterns()).containsExactly("/*");
            });
            var mvc = MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(registrations.get(0).getFilter(), registrations.get(1).getFilter()).build();
            mvc.perform(get("/internal/no-handler")).andExpect(status().isUnauthorized())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("SERVICE_AUTH_REQUIRED")));
            mvc.perform(get("/internal/probe").header("X-CityCopilot-Service-Token", "old")
                            .header("X-CityCopilot-Service-Actor", "batch-service"))
                    .andExpect(status().isOk()).andExpect(content().string("commerce-service"));
            mvc.perform(get("/private/probe")).andExpect(status().isUnauthorized());
            mvc.perform(get("/private/probe").header("authorization", "reader"))
                    .andExpect(status().isOk()).andExpect(content().string("7"));
            mvc.perform(get("/admin/probe").header("authorization", "reader")).andExpect(status().isForbidden());
            mvc.perform(get("/admin/probe").header("authorization", "merchant")).andExpect(status().isOk());
            // 注解在任何路径上都生效，不依赖手写路径清单；/admin 下无注解时默认要求 admin:access。
            mvc.perform(get("/private/guarded").header("authorization", "reader")).andExpect(status().isForbidden());
            mvc.perform(get("/private/guarded").header("authorization", "merchant")).andExpect(status().isOk());
            mvc.perform(get("/admin/plain").header("authorization", "consumer")).andExpect(status().isForbidden());
            mvc.perform(get("/admin/plain").header("authorization", "reader")).andExpect(status().isOk());
            for (String path : java.util.List.of("/health", "/uploads/probe")) {
                mvc.perform(get(path)).andExpect(status().isOk());
            }
            assertThatThrownBy(() -> mvc.perform(get("/private/fail").header("authorization", "reader")))
                    .hasRootCauseInstanceOf(IllegalStateException.class);
            assertThat(UserHolder.getUser()).isNull();
        }
    }

    @Test
    void sessionContextIsClearedOnAsyncHandoffErrorsAndRedisFailure() throws Exception {
        var redis = redis();
        var filter = new UserContextFilter();
        var sessions = new RefreshTokenInterceptor(redis);
        var request = new MockHttpServletRequest("GET", "/private/probe");
        request.addHeader("authorization", "reader");
        request.setAsyncSupported(true);
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            sessions.preHandle((jakarta.servlet.http.HttpServletRequest) req,
                    (jakarta.servlet.http.HttpServletResponse) res, new Object());
            assertThat(UserHolder.getUser().getId()).isEqualTo(7L);
            req.startAsync();
        });
        assertThat(UserHolder.getUser()).isNull();
        for (DispatcherType dispatch : java.util.List.of(DispatcherType.ASYNC, DispatcherType.ERROR)) {
            request.setDispatcherType(dispatch);
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                sessions.preHandle((jakarta.servlet.http.HttpServletRequest) req,
                        (jakarta.servlet.http.HttpServletResponse) res, new Object());
                assertThat(UserHolder.getUser().getId()).isEqualTo(7L);
            });
            assertThat(UserHolder.getUser()).isNull();
        }
        doThrow(new IllegalStateException("session storage unavailable")).when(redis)
                .expire(anyString(), anyLong(), any(java.util.concurrent.TimeUnit.class));
        request.setDispatcherType(DispatcherType.REQUEST);
        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            sessions.preHandle((jakarta.servlet.http.HttpServletRequest) req,
                    (jakarta.servlet.http.HttpServletResponse) res, new Object());
            fail("must stop");
        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(UserHolder.getUser()).isNull();
    }

    @Test
    void exhaustedRequestCapacityRejectsBeforeReadingTheSession() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "capacity", Map.of("citycopilot.http.max-concurrent", "1")));
            context.addBeanFactoryPostProcessor(factory ->
                    factory.setConversionService(ApplicationConversionService.getSharedInstance()));
            context.register(Config.class);
            context.refresh();
            var filters = context.getBeansOfType(FilterRegistrationBean.class).values().stream()
                    .sorted(java.util.Comparator.comparingInt(FilterRegistrationBean::getOrder))
                    .map(FilterRegistrationBean::getFilter).toArray(jakarta.servlet.Filter[]::new);
            var mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filters).build();
            var probe = context.getBean(Probe.class);
            var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var accepted = worker.submit(() -> mvc.perform(get("/private/hold").header("authorization", "reader")));
                assertThat(probe.entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var redis = context.getBean(StringRedisTemplate.class);
                clearInvocations(redis);
                mvc.perform(get("/private/probe").header("authorization", "reader"))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(content().string(org.hamcrest.Matchers.containsString("REQUEST_CAPACITY_EXHAUSTED")));
                verify(redis, never()).opsForHash();
                assertThat(UserHolder.getUser()).isNull();
                probe.release.countDown();
                accepted.get(5, java.util.concurrent.TimeUnit.SECONDS).andExpect(status().isOk());
            } finally {
                probe.release.countDown();
                worker.shutdownNow();
            }
        }
    }

    private static StringRedisTemplate redis() {
        var redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        when(redis.opsForHash().entries("login:token:reader"))
                .thenReturn(Map.of("id", "7", "role", "auditor"));
        when(redis.opsForHash().entries("login:token:merchant"))
                .thenReturn(Map.of("id", "8", "role", "merchant"));
        when(redis.opsForHash().entries("login:token:consumer"))
                .thenReturn(Map.of("id", "9", "role", "user"));
        return redis;
    }

    @Configuration
    @EnableWebMvc
    @Import({MvcConfig.class, Probe.class})
    static class Config {
        @Bean StringRedisTemplate redisTemplate() { return redis(); }
        @Bean UploadProperties uploadProperties() { return new UploadProperties(); }
        @Bean PermissionPolicy permissionPolicy() { return new PermissionPolicy(); }
        @Bean com.citycopilot.core.config.SecurityProperties securityProperties() {
            return new com.citycopilot.core.config.SecurityProperties();
        }
        @Bean InternalApiProperties internalApiProperties() {
            var properties = new InternalApiProperties();
            properties.setServiceTokens(Map.of("commerce-service", "new"));
            properties.setPreviousServiceTokens(Map.of("commerce-service", "old"));
            return properties;
        }
    }

    @RestController
    static class Probe {
        final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        @GetMapping("/private/hold") String hold() throws InterruptedException {
            entered.countDown();
            if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("check timeout");
            return "allowed";
        }
        @GetMapping("/internal/probe") String internal(
                @RequestAttribute(InternalServiceTokenFilter.AUTHENTICATED_ACTOR_ATTRIBUTE) String actor) { return actor; }
        @GetMapping("/private/probe") String user() { return UserHolder.getUser().getId().toString(); }
        @GetMapping("/admin/probe") @RequirePermission(PermissionCodes.BOOKING_MANAGE)
        String admin() { return "allowed"; }
        @GetMapping({"/health", "/uploads/probe"}) String publicRoute() { return "public"; }
        @GetMapping("/private/fail") String failRequest() { throw new IllegalStateException("business failure"); }
        @GetMapping("/private/guarded") @RequirePermission(PermissionCodes.BOOKING_MANAGE)
        String guarded() { return "guarded"; }
        @GetMapping("/admin/plain") String adminPlain() { return "plain"; }
    }
}
