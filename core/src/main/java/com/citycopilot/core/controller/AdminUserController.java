package com.citycopilot.core.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.UserAdminDTO;
import com.citycopilot.core.entity.User;
import com.citycopilot.core.security.PermissionCodes;
import com.citycopilot.core.security.PermissionPolicy;
import com.citycopilot.core.security.RequirePermission;
import com.citycopilot.core.service.IUserService;
import com.citycopilot.core.service.OperationLogService;
import com.citycopilot.core.utils.SystemConstants;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.citycopilot.core.utils.RedisConstants.LOGIN_USER_KEY;

@RestController
@RequestMapping("/admin/users")
@RequirePermission(PermissionCodes.USER_WRITE)
public class AdminUserController {

    @Resource
    private IUserService userService;

    @Resource
    private OperationLogService operationLogService;

    @Resource
    private PermissionPolicy permissionPolicy;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @GetMapping
    public Result queryUsers(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "keyword", required = false) String keyword
    ) {
        int pageNo = current == null || current < 1 ? 1 : current;
        Page<User> page = userService.query()
                .and(StrUtil.isNotBlank(keyword), wrapper -> wrapper
                        .like("phone", keyword)
                        .or()
                        .like("nick_name", keyword)
                        .or()
                        .like("role", keyword))
                .orderByDesc("id")
                .page(new Page<>(pageNo, SystemConstants.MAX_PAGE_SIZE));
        Page<UserAdminDTO> viewPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        List<UserAdminDTO> records = page.getRecords().stream()
                .map(user -> BeanUtil.copyProperties(user, UserAdminDTO.class))
                .toList();
        viewPage.setRecords(records);
        return Result.ok(viewPage);
    }

    @PutMapping("/{id}/role")
    public Result updateRole(
            @PathVariable("id") Long id,
            @RequestParam("role") String role
    ) {
        if (StrUtil.isBlank(role)) {
            return Result.fail("BAD_REQUEST", "角色不能为空");
        }
        String normalizedRole = role.trim();
        if (!"user".equals(normalizedRole) && !permissionPolicy.effectiveRolePermissions().containsKey(normalizedRole)) {
            return Result.fail("BAD_REQUEST", "角色未在权限配置中定义");
        }
        User before = userService.getById(id);
        if (before == null) {
            return Result.fail("USER_NOT_FOUND", "用户不存在");
        }
        User update = new User()
                .setId(id)
                .setRole(normalizedRole)
                .setUpdateTime(LocalDateTime.now());
        userService.updateById(update);
        User after = userService.getById(id);
        long revokedTokens = revokeUserTokens(id);
        operationLogService.record("user", "user_role_update", "user", id, String.valueOf(id), toAdminDTO(before), toAdminDTO(after), true, null);
        if (revokedTokens > 0) {
            operationLogService.record("user", "user_token_revoke", "user", id, String.valueOf(id), null, revokedTokens, true, null);
        }
        return Result.ok(toAdminDTO(after));
    }

    private UserAdminDTO toAdminDTO(User user) {
        return user == null ? null : BeanUtil.copyProperties(user, UserAdminDTO.class);
    }

    private long revokeUserTokens(Long userId) {
        if (userId == null) {
            return 0;
        }
        List<String> tokenKeys = new ArrayList<>();
        try (Cursor<String> cursor = stringRedisTemplate.scan(ScanOptions.scanOptions().match(LOGIN_USER_KEY + "*").count(500).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                Object tokenUserId = stringRedisTemplate.opsForHash().get(key, "id");
                if (String.valueOf(userId).equals(String.valueOf(tokenUserId))) {
                    tokenKeys.add(key);
                }
            }
        } catch (Exception ignored) {
            return 0;
        }
        if (tokenKeys.isEmpty()) {
            return 0;
        }
        stringRedisTemplate.delete(tokenKeys);
        return tokenKeys.size();
    }
}
