package com.citycopilot.core.service.impl;

import com.citycopilot.core.dto.UserDTO;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
}
