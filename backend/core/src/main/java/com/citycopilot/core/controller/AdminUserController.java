package com.citycopilot.core.controller;

import com.citycopilot.core.dto.Result;
import com.citycopilot.contract.security.PermissionCodes;
import com.citycopilot.core.security.RequirePermission;
import com.citycopilot.core.service.IUserService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

/** 后台用户入口；角色权限、审计与会话撤销由 Identity 服务执行。 */
@RestController
@RequestMapping("/admin/users")
@RequirePermission(PermissionCodes.USER_WRITE)
public class AdminUserController {
    @Resource
    private IUserService userService;

    @GetMapping
    public Result queryUsers(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "keyword", required = false) String keyword) {
        return userService.queryAdminUsers(current, keyword);
    }

    @PutMapping("/{id}/role")
    public Result updateRole(@PathVariable("id") Long id, @RequestParam("role") String role) {
        return userService.updateUserRole(id, role);
    }
}
