package com.citycopilot.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.entity.Follow;
import com.citycopilot.core.mapper.FollowMapper;
import com.citycopilot.core.service.IFollowService;
import com.citycopilot.core.utils.UserHolder;
import org.springframework.stereotype.Service;

/** Follow commands and common-follow queries share the same durable relationship table. */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        if (followUserId == null || followUserId <= 0 || isFollow == null) {
            return Result.fail("INVALID_FOLLOW", "关注操作需要有效的用户和目标状态");
        }
        Long userId = UserHolder.getUser().getId();
        if (isFollow) baseMapper.ensureFollow(userId, followUserId);
        else remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserId));
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        return Result.ok(query().eq("user_id", UserHolder.getUser().getId())
                .eq("follow_user_id", followUserId).count() > 0);
    }
}
