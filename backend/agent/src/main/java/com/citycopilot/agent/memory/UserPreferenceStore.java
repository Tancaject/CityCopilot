package com.citycopilot.agent.memory;

import java.util.List;

/** 长期偏好的持久化端口，所有写操作都必须限定用户作用域。 */
public interface UserPreferenceStore {

    /** 按“用户、类型、规范键”插入或更新偏好，并累计提及次数。 */
    void upsert(long userId, ExplicitPreference preference);

    /** @return 指定用户最近更新的有限条偏好 */
    List<UserPreference> list(long userId, int limit);

    /** @return 记录存在且属于该用户并成功删除时为 true */
    boolean delete(long userId, long preferenceId);

    /** @return 按规范键删除的记录数 */
    int deleteByKey(long userId, PreferenceType type, String key);

    /** @return 清除该用户全部长期偏好的记录数 */
    int deleteAll(long userId);
}
