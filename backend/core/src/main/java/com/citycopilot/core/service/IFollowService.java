package com.citycopilot.core.service;

import com.citycopilot.core.dto.Result;

/**
 * 用户关注关系服务，以数据库唯一关系为事实来源。
 *
 * <p>博客关注流与共同关注查询都依赖这里维护的关系数据。</p>
 */
public interface IFollowService {

    /**
     * 关注或取关目标用户。
     *
     * @param followUserId 目标用户主键
     * @param isFollow {@code true} 表示关注，{@code false} 表示取关
     * @return 操作结果
     */
    Result follow(Long followUserId, Boolean isFollow);

    /**
     * 判断当前用户是否已关注目标用户。
     *
     * @param followUserId 目标用户主键
     * @return 布尔查询结果
     */
    Result isFollow(Long followUserId);
}
