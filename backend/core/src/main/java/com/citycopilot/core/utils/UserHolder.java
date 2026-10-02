package com.citycopilot.core.utils;

import com.citycopilot.core.dto.UserDTO;

/** 在单个 MVC 请求线程内保存当前用户，供控制器、服务和权限策略读取。 */
public class UserHolder {
    /** 当前线程的最小用户快照；请求结束必须调用 {@link #removeUser()}。 */
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    /** @param user 当前请求已认证的用户快照 */
    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    /** @return 当前请求用户；匿名或尚未恢复会话时为 {@code null} */
    public static UserDTO getUser(){
        return tl.get();
    }

    /** 清除线程变量，避免 servlet 线程池复用时串号。 */
    public static void removeUser(){
        tl.remove();
    }
}
