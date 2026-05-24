package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.config.SecurityProperties;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private SecurityProperties securityProperties;

    @Override
    public Result sendCode(String phone, HttpSession session) {//发送验证码
        // 1、提交手机号进行校验
        if (RegexUtils.isPhoneInvalid(phone)){
            // 2、如果不符合，返回错误信息
            return Result.fail("USER_PHONE_INVALID", "手机号格式错误！");
        }
        if (isLoginCodeRateLimited(phone)) {
            return Result.fail("LOGIN_CODE_RATE_LIMITED", "验证码发送过于频繁，请稍后再试");
        }
        // 3、符合则生成验证码
        String code= RandomUtil.randomNumbers(6);//生成6位的验证码
        // 4、保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);//以用户的手机号作为key进行保存,有效期2分钟
        // 5、发送验证码
        log.debug("验证码发送成功，验证码:{}",code);
        // 6、返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone =loginForm.getPhone();
        // 提交手机号进行校验
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())){
            // 如果不符合，返回错误信息
            return Result.fail("USER_PHONE_INVALID", "手机号格式错误！");
        }
        //从redis获取并校验验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        String code=loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
            return Result.fail("LOGIN_CODE_INVALID", "验证码不一致！");
        }
        stringRedisTemplate.delete(LOGIN_CODE_KEY + phone);
        //验证码一致 根据手机号查用户
        User user = query().eq("phone", phone).one();
        //用户不存在就创建新用户
        if (user == null) {
            user = createUserWithPhone(phone);
        }
        //保存用户信息到redis中
        //随机生成一个token，作为登录令牌
        String token= UUID.randomUUID().toString();
        //将user对象转换成hash进行存储，所有值转成String
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        //存储到redis
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY+token,userMap);
        //设置token有效期 30分钟
        String tokenKey=LOGIN_USER_KEY+token;
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);
        //返回token
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        //创建用户
        User user=new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        //保存用户
        save(user);
        return user;
    }

    @Override
    public Result logout(HttpServletRequest request) {
        String token = request.getHeader("authorization");
        stringRedisTemplate.delete(LOGIN_USER_KEY + token);
        return Result.ok();
    }

    @Override
    public Result sign() {
        //获取登录用户id
        Long userId = UserHolder.getUser().getId();
        //获取当前时间
        LocalDateTime time = LocalDateTime.now();
        //拼接key
        String keySuffix = time.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key=USER_SIGN_KEY+userId+":"+keySuffix;
        //获取今天是本月第几天
        int dayofMonth=time.getDayOfMonth();
        //判断今天是否已签到
        Boolean signed = stringRedisTemplate.opsForValue().getBit(key, dayofMonth - 1);
        if (Boolean.TRUE.equals(signed)) {
            return Result.fail("SIGN_ALREADY_DONE", "今天已经签到了");
        }
        //写入redis
        stringRedisTemplate.opsForValue().setBit(key,dayofMonth-1,true);
        return Result.ok();
    }

    @Override
    public Result isSignedToday() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime time = LocalDateTime.now();
        String key = USER_SIGN_KEY + userId + ":" + time.format(DateTimeFormatter.ofPattern("yyyyMM"));
        Boolean signed = stringRedisTemplate.opsForValue().getBit(key, time.getDayOfMonth() - 1);
        return Result.ok(Boolean.TRUE.equals(signed));
    }

    @Override
    public Result signCount() {
        //获取登录用户id
        Long userId = UserHolder.getUser().getId();
        //获取当前时间
        LocalDateTime time = LocalDateTime.now();
        //拼接key
        String keySuffix = time.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key=USER_SIGN_KEY+userId+":"+keySuffix;
        //获取今天是本月第几天
        int dayofMonth=time.getDayOfMonth();
        //获取本月截至今天的签到记录
        List<Long> results = stringRedisTemplate.opsForValue().bitField(
                key, BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayofMonth)).valueAt(0)
        );
        if (results==null||results.isEmpty()){
            return Result.ok(0);
        }
        Long num=results.get(0);
        if (num==null||num==0){
            return Result.ok(0);
        }

        // 循环遍历
        int count=0;
        while (true) {
            //与1做与运算，得到数字的最后一个bit位
            if ((num&1)==0){
                //为0 说明未签到 结束
                break;
            }else {
                //为1 说明签到了 计数+1
                count++;
            }
            //数字右移一位
            num>>>=1;
        }
        return Result.ok(count);
    }

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
