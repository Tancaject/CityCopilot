package com.citycopilot.core.service;

import com.citycopilot.core.dto.LoginFormDTO;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.UserDTO;
import java.util.Collection;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 用户认证与每日签到服务。
 *
 * <p>验证码、登录令牌和签到位图存放在 Redis，用户基本资料持久化到 MySQL。</p>
 */
public interface IUserService {

    UserDTO findUserProfile(Long userId);

    /** 批量公开资料；返回数据库顺序，由调用方按其业务顺序组装。 */
    List<UserDTO> findUserProfiles(Collection<Long> userIds);

    long countUsers();

    Result queryAdminUsers(Integer current, String keyword);

    /** 校验角色配置、更新角色、撤销旧会话并记录原有审计。 */
    Result updateUserRole(Long userId, String role);

    /**
     * 为手机号生成并发送登录验证码，同时建立频率限制。
     *
     * @param phone 待验证手机号
     * @param clientIp 请求来源 IP，用于按来源限流
     * @return 发送结果
     */
    Result sendCode(String phone, String clientIp);

    /**
     * 校验验证码并签发登录令牌；首次登录会自动创建用户。
     *
     * @param loginForm 手机号和验证码
     * @return 含登录令牌的结果
     */
    Result login(LoginFormDTO loginForm);

    /**
     * 删除请求中令牌对应的 Redis 登录态。
     *
     * @param request 携带认证令牌的 HTTP 请求
     * @return 退出结果
     */
    Result logout(HttpServletRequest request);
}
