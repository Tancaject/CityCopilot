package com.citycopilot.core.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.citycopilot.contract.tool.AgentTool;
import com.citycopilot.contract.tool.ToolSideEffect;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.entity.Follow;
import com.citycopilot.core.security.PermissionCodes;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IFollowService extends IService<Follow> {

    @AgentTool(
            name = "set_follow_user",
            description = "Follow or unfollow another user",
            requiredPermissions = {PermissionCodes.USER_WRITE},
            idempotent = false,
            sideEffect = ToolSideEffect.NON_IDEMPOTENT_WRITE,
            scope = "community:follow"
    )
    Result follow(Long followUserId, Boolean isFollow);

    @AgentTool(name = "check_follow_user", description = "Check whether current user follows another user")
    Result isFollow(Long followUserId);

    @AgentTool(name = "list_common_follows", description = "List common followed users with another user")
    Result followCommons(Long followUserId);
}
