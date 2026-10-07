package com.citycopilot.core.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.citycopilot.core.dto.UserAdminDTO;
import com.citycopilot.core.security.PermissionPolicy;
import com.citycopilot.core.service.OperationLogService;
import com.citycopilot.core.utils.SystemConstants;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.citycopilot.core.config.SecurityProperties;
import com.citycopilot.core.dto.LoginFormDTO;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.UserDTO;
import com.citycopilot.core.entity.User;
import com.citycopilot.core.mapper.UserMapper;
import com.citycopilot.core.service.IUserService;
import com.citycopilot.core.utils.RegexUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.citycopilot.core.utils.RedisConstants.*;
import static com.citycopilot.core.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * 用户认证与签到服务实现。
 *
 * <p>手机号验证码和无状态登录令牌保存在 Redis；签到按月使用 Bitmap 压缩存储。</p>
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    /** Redis 客户端，承载验证码、登录态、限流桶和签到位图。 */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 安全配置，提供每个手机号的验证码发送频率上限。 */
    @Resource
    private SecurityProperties securityProperties;

    @Resource
    private PermissionPolicy permissionPolicy;

    @Resource
    private OperationLogService operationLogService;

    @Override
    public UserDTO findUserProfile(Long userId) {
        User user = getById(userId);
        return user == null ? null : BeanUtil.copyProperties(user, UserDTO.class);
    }

    @Override
    public List<UserDTO> findUserProfiles(Collection<Long> userIds) {
        return listByIds(userIds).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class)).toList();
    }

    @Override
    public long countUsers() {
        return count();
    }

    @Override
    public Result queryAdminUsers(Integer current, String keyword) {
        int pageNo = current == null || current < 1 ? 1 : current;
        Page<User> page = query()
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

    @Override
    public Result updateUserRole(Long id, String role) {
        if (StrUtil.isBlank(role)) {
            return Result.fail("BAD_REQUEST", "角色不能为空");
        }
        String normalizedRole = role.trim();
        if (!"user".equals(normalizedRole) && !permissionPolicy.effectiveRolePermissions().containsKey(normalizedRole)) {
            return Result.fail("BAD_REQUEST", "角色未在权限配置中定义");
        }
        User before = getById(id);
        if (before == null) {
            return Result.fail("USER_NOT_FOUND", "用户不存在");
        }
        User update = new User()
                .setId(id)
                .setRole(normalizedRole)
                .setUpdateTime(LocalDateTime.now());
        updateById(update);
        User after = getById(id);
        // 旧令牌可能缓存原角色信息，角色更新后必须尽快使其失效。
        long revokedTokens = revokeUserTokens(id);
        // 记录前后快照，支持追查权限提升或降级操作。
        operationLogService.record("user", "user_role_update", "user", id, String.valueOf(id), toAdminDTO(before), toAdminDTO(after), true, null);
        if (revokedTokens > 0) {
            operationLogService.record("user", "user_token_revoke", "user", id, String.valueOf(id), null, revokedTokens, true, null);
        }
        return Result.ok(toAdminDTO(after));
    }

    /**
     * 将用户实体转换为后台接口允许暴露的视图。
     *
     * @param user 用户实体，可为空
     * @return 后台用户 DTO，输入为空时返回 {@code null}
     */
    private UserAdminDTO toAdminDTO(User user) {
        return user == null ? null : BeanUtil.copyProperties(user, UserAdminDTO.class);
    }

    /**
     * 扫描登录令牌并删除属于指定用户的全部会话。
     *
     * <p>使用 SCAN 避免阻塞 Redis；异常时返回 0，使角色数据库变更不因缓存故障失败。</p>
     *
     * @param userId 需要撤销令牌的用户主键
     * @return 成功匹配并请求删除的令牌数量
     */
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

    @Override
    public Result sendCode(String phone, HttpSession session) {
        if (RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("USER_PHONE_INVALID", "手机号格式错误！");
        }
        if (isLoginCodeRateLimited(phone)) {
            return Result.fail("LOGIN_CODE_RATE_LIMITED", "验证码发送过于频繁，请稍后再试");
        }
        String code= RandomUtil.randomNumbers(6);
        // 验证码设置独立 TTL，过期后即使客户端仍持有旧会话也不能登录。
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        log.debug("验证码发送成功，验证码:{}",code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone =loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())){
            return Result.fail("USER_PHONE_INVALID", "手机号格式错误！");
        }
        // 验证码以手机号为键，从 Redis 校验成功后立即删除，防止重复使用。
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        String code=loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
            return Result.fail("LOGIN_CODE_INVALID", "验证码不一致！");
        }
        stringRedisTemplate.delete(LOGIN_CODE_KEY + phone);
        User user = query().eq("phone", phone).one();
        if (user == null) {
            user = createUserWithPhone(phone);
        }
        String token= UUID.randomUUID().toString();
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = toRedisHash(userDTO);
        // 令牌映射到用户 Hash，认证拦截器可直接恢复轻量 UserDTO。
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY+token,userMap);
        String tokenKey=LOGIN_USER_KEY+token;
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);
        return Result.ok(token);
    }

    /**
     * 首次手机号登录时创建最小用户记录。
     *
     * @param phone 已通过格式和验证码校验的手机号
     * @return 已持久化且带主键的新用户
     */
    private User createUserWithPhone(String phone) {
        User user=new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        save(user);
        return user;
    }

    /**
     * 将用户摘要转换成适合 Redis Hash 的纯字符串值。
     *
     * @param userDTO 待写入登录态的用户摘要
     * @return 不含空值的字段映射
     */
    static Map<String, Object> toRedisHash(UserDTO userDTO) {
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true));
        userMap.entrySet().removeIf(entry -> entry.getValue() == null);
        userMap.replaceAll((fieldName, fieldValue) -> fieldValue.toString());
        return userMap;
    }

    @Override
    public Result logout(HttpServletRequest request) {
        String token = request.getHeader("authorization");
        stringRedisTemplate.delete(LOGIN_USER_KEY + token);
        return Result.ok();
    }

    /**
     * 使用按分钟分桶的 Redis 计数器限制单手机号验证码请求。
     *
     * <p>Redis 异常时放行验证码主流程，避免缓存故障完全阻断登录。</p>
     *
     * @param phone 待检查手机号
     * @return {@code true} 表示当前分钟已超过配置上限
     */
    private boolean isLoginCodeRateLimited(String phone) {
        Integer limit = securityProperties == null ? null : securityProperties.getLoginCodeRateLimitPerPhone();
        if (limit == null || limit <= 0) {
            return false;
        }
        String bucket = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
        String key = LOGIN_CODE_RATE_LIMIT_KEY + phone + ":" + bucket;
        try {
            Long count = stringRedisTemplate.opsForValue().increment(key);
            if (Long.valueOf(1L).equals(count)) {
                stringRedisTemplate.expire(key, 70, TimeUnit.SECONDS);
            }
            return count != null && count > limit;
        } catch (Exception error) {
            log.debug("Login code rate limit skipped: {}", error.getMessage());
            return false;
        }
    }
}
