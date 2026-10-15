package com.citycopilot.core.service.impl;

import com.citycopilot.core.config.SecurityProperties;
import com.citycopilot.core.dto.LoginFormDTO;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.UserDTO;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceImplTest {

    @Test
    void toRedisHashSkipsNullValuesAndStringifiesNonNullValues() {
        UserDTO user = new UserDTO();
        user.setId(1L);
        user.setNickName("小鱼同学");
        user.setRole("user");

        Map<String, Object> hash = UserServiceImpl.toRedisHash(user);

        assertEquals("1", hash.get("id"));
        assertEquals("小鱼同学", hash.get("nickName"));
        assertEquals("user", hash.get("role"));
        assertFalse(hash.containsKey("icon"));
        assertFalse(hash.containsKey("permissions"));
    }

    private static final String PHONE = "13800000000";

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    private UserServiceImpl service() {
        when(redis.opsForValue()).thenReturn(values);
        UserServiceImpl service = new UserServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "securityProperties", new SecurityProperties());
        return service;
    }

    @Test
    void wrongCodeIsInvalidatedAfterMaxAttempts() {
        UserServiceImpl service = service();
        when(values.get("login:code:" + PHONE)).thenReturn("123456");
        when(values.increment("login:code-attempts:" + PHONE)).thenReturn(5L);
        LoginFormDTO form = new LoginFormDTO();
        form.setPhone(PHONE);
        form.setCode("000000");

        Result result = service.login(form);

        assertEquals("LOGIN_CODE_INVALID", result.getCode());
        verify(redis).delete(List.of("login:code:" + PHONE, "login:code-attempts:" + PHONE));
    }

    @Test
    void sendCodeIsRejectedWhenSourceIpExceedsLimit() {
        UserServiceImpl service = service();
        when(values.increment(startsWith("limit:login:code:"))).thenReturn(1L);
        when(values.increment(startsWith("limit:login:code-ip:"))).thenReturn(21L);

        Result result = service.sendCode(PHONE, "203.0.113.9");

        assertEquals("LOGIN_CODE_RATE_LIMITED", result.getCode());
        verify(values, never()).set(anyString(), anyString(), anyLong(), any());
    }

    @Test
    void sendCodeFailsClosedWhenRateLimitStoreIsUnavailable() {
        UserServiceImpl service = service();
        when(values.increment(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThrows(RedisConnectionFailureException.class, () -> service.sendCode(PHONE, "203.0.113.9"));
        verify(values, never()).set(anyString(), anyString(), anyLong(), any());
    }
}
