package com.citycopilot.core.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.citycopilot.core.agent.AgentTool;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.entity.Follow;
import com.citycopilot.core.security.PermissionCodes;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    @AgentTool(
            name = "set_follow_user",
            description = "Follow or unfollow another user",
            requiredPermissions = {PermissionCodes.USER_WRITE},
            idempotent = false
    )
    Result follow(Long followUserId, Boolean isFollow);

    @AgentTool(name = "check_follow_user", description = "Check whether current user follows another user")
    Result isFollow(Long followUserId);

    @AgentTool(name = "list_common_follows", description = "List common followed users with another user")
    Result followCommons(Long followUserId);
}
