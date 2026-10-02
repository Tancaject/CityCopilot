package com.citycopilot.core.service;

import com.citycopilot.core.dto.UserProfileDTO;

/**
 * 用户扩展资料服务。
 *
 * <p>账号认证信息由 {@link IUserService} 管理，本接口只提供个人简介等非认证资料的公开视图。</p>
 */
public interface IUserInfoService {
    /** @return 用户公开扩展资料；未创建资料时返回 null */
    UserProfileDTO profile(Long userId);
}
