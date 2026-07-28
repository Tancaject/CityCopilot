package com.citycopilot.agent.memory;

import java.util.List;

public interface UserPreferenceStore {

    void upsert(long userId, ExplicitPreference preference);

    List<UserPreference> list(long userId, int limit);

    boolean delete(long userId, long preferenceId);

    int deleteAll(long userId);
}
