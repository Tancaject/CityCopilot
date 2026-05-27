package com.citycopilot.core.controller;


import cn.hutool.core.bean.BeanUtil;
import com.citycopilot.core.dto.LoginFormDTO;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.UserDTO;
import com.citycopilot.core.entity.User;
import com.citycopilot.core.entity.UserInfo;
import com.citycopilot.core.security.PermissionPolicy;
import com.citycopilot.core.service.IUserInfoService;
import com.citycopilot.core.service.IUserService;
import com.citycopilot.core.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import com.citycopilot.core.utils.RedisConstants;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDate;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private PermissionPolicy permissionPolicy;

    /**
     * 发送手机验证码
     */
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone, HttpSession session) {
        return userService.sendCode(phone,session);
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session){
        return userService.login(loginForm,session) ;
    }

    /**
     * 登出功能
     * @return 无
     */
    @PostMapping("/logout")
    public Result logout(HttpServletRequest request){
        return userService.logout(request);
    }

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

    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId){
        // 查询详情
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            // 没有详情，应该是第一次查看详情
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        // 返回
        return Result.ok(info);
    }

    @GetMapping("/{id}")
    public Result queryUserById(@PathVariable("id")Long userId){
        User user=userService.getById(userId);
        if (user==null){
            return Result.ok();
        }
        UserDTO userDTO= BeanUtil.copyProperties(user,UserDTO.class);
        return Result.ok(userDTO);
    }
    @PostMapping("/sign")
    public Result sign(){//签到功能
        return userService.sign();
    }
    @GetMapping("/sign/count")
    public Result signCount(){
        return userService.signCount();
    }
    @GetMapping("/sign/today")
    public Result isSignedToday(){
        return userService.isSignedToday();
    }

    @GetMapping("/uv")
    public Result queryUV(@RequestParam(defaultValue = "0") Integer daysAgo) {
        String key = RedisConstants.UV_KEY +
                LocalDate.now().minusDays(daysAgo).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        Long uv = stringRedisTemplate.opsForHyperLogLog().size(key);
        return Result.ok(uv);
    }
}
