package com.citycopilot.core.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.citycopilot.core.dto.UserProfileDTO;
import com.citycopilot.core.entity.UserInfo;
import com.citycopilot.core.mapper.UserInfoMapper;
import com.citycopilot.core.service.IUserInfoService;
import org.springframework.stereotype.Service;

/**
 * 用户扩展资料服务的 MyBatis-Plus 实现。
 *
 * <p>复用 {@link UserInfoMapper} 读取实体，在服务边界组装公开资料视图。</p>
 */
@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {
    @Override
    public UserProfileDTO profile(Long userId) {
        UserInfo info = getById(userId);
        if (info == null) return null;
        return new UserProfileDTO(info.getUserId(), info.getCity(), info.getIntroduce(), info.getFans(),
                info.getFollowee(), info.getGender(), info.getBirthday(), info.getCredits(), info.getLevel(),
                null, null);
    }
}
