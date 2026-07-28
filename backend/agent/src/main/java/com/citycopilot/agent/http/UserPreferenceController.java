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

@RestController
@RequestMapping("/agent/memory/preferences")
public class UserPreferenceController {
    private final LongTermPreferenceMemory preferenceMemory;

    public UserPreferenceController(LongTermPreferenceMemory preferenceMemory) {
        this.preferenceMemory = preferenceMemory;
    }

    @GetMapping
    public List<UserPreference> list() {
        Long userId = currentUser().id();
        try {
            return preferenceMemory.list(userId);
        } catch (RuntimeException error) {
            throw unavailable(error);
        }
    }

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
