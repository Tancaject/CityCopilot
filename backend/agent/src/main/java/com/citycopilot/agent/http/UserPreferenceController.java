package com.citycopilot.agent.http;

import com.citycopilot.agent.memory.LongTermPreferenceMemory;
import com.citycopilot.agent.memory.UserPreference;
import com.citycopilot.agent.security.AgentRequestContextHolder;
import com.citycopilot.agent.security.AgentSecurity;
import com.citycopilot.contract.security.ServiceUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * 用户长期偏好的查询与遗忘接口。
 *
 * <p>所有操作绑定当前登录用户，客户端不能通过参数读写他人偏好；存储异常统一映射为 503。</p>
 */
@RestController
@RequestMapping("/agent/memory/preferences")
public class UserPreferenceController {
    /** 长期偏好的领域服务。 */
    private final LongTermPreferenceMemory preferenceMemory;

    public UserPreferenceController(LongTermPreferenceMemory preferenceMemory) {
        this.preferenceMemory = preferenceMemory;
    }

    /** @return 当前登录用户的全部有效偏好 */
    @GetMapping
    public List<UserPreference> list() {
        Long userId = currentUser().id();
        try {
            return preferenceMemory.list(userId);
        } catch (RuntimeException error) {
            throw unavailable(error);
        }
    }

    /**
     * 删除当前用户拥有的单条偏好。
     *
     * @param preferenceId 偏好记录主键
     * @return 删除确认；记录不存在或不属于当前用户时返回 404
     */
    @DeleteMapping("/{preferenceId}")
    public Map<String, Object> forget(@PathVariable long preferenceId) {
        Long userId = currentUser().id();
        try {
            if (!preferenceMemory.forget(userId, preferenceId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PREFERENCE_NOT_FOUND");
            }
            return Map.of("deleted", true, "preferenceId", preferenceId);
        } catch (ResponseStatusException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable(error);
        }
    }

    /** @return 当前用户本次清除的偏好数量 */
    @DeleteMapping
    public Map<String, Object> forgetAll() {
        Long userId = currentUser().id();
        try {
            int deleted = preferenceMemory.forgetAll(userId);
            return Map.of("deleted", deleted);
        } catch (RuntimeException error) {
            throw unavailable(error);
        }
    }

    /** @return 已完成认证的当前用户；匿名请求会被拒绝 */
    private ServiceUser currentUser() {
        return AgentSecurity.requireAuthenticated(AgentRequestContextHolder.get().user());
    }

    private ResponseStatusException unavailable(RuntimeException error) {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "PREFERENCE_MEMORY_UNAVAILABLE",
                error
        );
    }
}
