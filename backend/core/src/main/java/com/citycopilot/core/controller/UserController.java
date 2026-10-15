package com.citycopilot.core.controller;


import cn.hutool.core.bean.BeanUtil;
import com.citycopilot.core.dto.LoginFormDTO;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.UserDTO;
import com.citycopilot.core.security.PermissionPolicy;
import com.citycopilot.core.service.IUserService;
import com.citycopilot.core.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.redis.core.StringRedisTemplate;


/**
 * 用户认证、资料与签到控制器。
 *
 * <p>对外只返回 {@link UserDTO} 等安全视图；验证码、令牌、签到位图等状态由用户服务
 * 和 Redis 维护，控制器负责协议参数与响应适配。</p>
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    /** 用户领域服务，处理验证码、登录令牌、登出和签到。 */
    @Resource
    private IUserService userService;

    /** Redis 客户端，用于读取按日期统计的 HyperLogLog UV。 */
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /** 角色权限策略，用于刷新会话响应中的权限集合。 */
    @Resource
    private PermissionPolicy permissionPolicy;

    /**
     * 向指定手机号发送登录验证码。
     *
     * @param phone 接收验证码的手机号
     * @param request 用于取得经可信代理处理后的客户端地址
     * @return 发送成功或手机号校验、频率限制等失败结果
     */
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone, HttpServletRequest request) {
        // 经 server.forward-headers-strategy 处理后，remoteAddr 是可信代理转发的真实客户端地址。
        return userService.sendCode(phone, request.getRemoteAddr());
    }

    /**
     * 使用验证码或密码登录，并创建后续请求使用的认证状态。
     *
     * @param loginForm 手机号及验证码或密码
     * @return 登录令牌或认证失败结果
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm){
        return userService.login(loginForm);
    }

    /**
     * 注销当前请求对应的登录令牌。
     *
     * @param request 携带当前认证令牌的 HTTP 请求
     * @return 登出处理结果
     */
    @PostMapping("/logout")
    public Result logout(HttpServletRequest request){
        return userService.logout(request);
    }

    /**
     * 查询当前登录用户的安全会话视图。
     *
     * <p>权限按当前角色配置重新计算，避免直接信任令牌缓存中的旧权限集合。</p>
     *
     * @return 当前用户 DTO；未登录时返回空数据
     */
    @GetMapping("/me")
    public Result me(){
        UserDTO user= UserHolder.getUser();
        if (user == null) {
            return Result.ok();
        }
        UserDTO response = BeanUtil.copyProperties(user, UserDTO.class);
        response.setPermissions(permissionPolicy.permissionsForRole(response.getRole()));
        return Result.ok(response);
    }

    /**
     * 查询指定用户的公开基础信息。
     *
     * @param userId 用户主键
     * @return 经过 {@link UserDTO} 过滤的用户信息；不存在时返回空数据
     */
    @GetMapping("/{id}")
    public Result queryUserById(@PathVariable("id")Long userId){
        return Result.ok(userService.findUserProfile(userId));
    }
}
