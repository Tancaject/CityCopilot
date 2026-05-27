package com.citycopilot.core.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.citycopilot.core.dto.LoginFormDTO;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);

    Result logout(HttpServletRequest request);

    Result sign();

    Result signCount();

    Result isSignedToday();
}
