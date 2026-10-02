package com.citycopilot.core.service;

import com.citycopilot.contract.tool.AgentTool;
import com.citycopilot.contract.tool.ToolSideEffect;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.security.PermissionCodes;

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
    @AgentTool(
            name = "set_follow_user",
            description = "Follow or unfollow another user",
            requiredPermissions = {PermissionCodes.USER_WRITE},
            idempotent = true,
            sideEffect = ToolSideEffect.IDEMPOTENT_WRITE,
            scope = "community:follow"
    )
    Result follow(Long followUserId, Boolean isFollow);

    /**
     * 判断当前用户是否已关注目标用户。
     *
     * @param followUserId 目标用户主键
     * @return 布尔查询结果
     */
    @AgentTool(name = "check_follow_user", description = "Check whether current user follows another user")
    Result isFollow(Long followUserId);

    /**
     * 查询当前用户与指定用户共同关注的人。
     *
     * @param followUserId 用于比较关注集合的用户主键
     * @return 共同关注用户摘要列表
     */
    @AgentTool(name = "list_common_follows", description = "List common followed users with another user")
    Result followCommons(Long followUserId);
}
