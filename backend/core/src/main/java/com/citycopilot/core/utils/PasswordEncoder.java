package com.citycopilot.core.utils;


import cn.hutool.core.util.RandomUtil;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;

/**
 * 兼容现有 {@code salt@digest} 存储格式的密码编码工具。
 *
 * <p>每次编码生成独立盐；登录校验会从已存字符串中取出原盐重新计算摘要。</p>
 */
public class PasswordEncoder {

    /**
     * @param password 原始密码
     * @return 包含随机盐和摘要的存储字符串
     */
    public static String encode(String password) {
        // 随机盐使相同明文不会生成相同的存储字符串。
        String salt = RandomUtil.randomString(20);
        return encode(password,salt);
    }
    /** @return 使用指定盐生成兼容旧数据格式的摘要字符串 */
    private static String encode(String password, String salt) {
        return salt + "@" + DigestUtils.md5DigestAsHex((password + salt).getBytes(StandardCharsets.UTF_8));
    }
    /**
     * @param encodedPassword 数据库存储的 salt@digest
     * @param rawPassword 待验证的原始密码
     * @return 两者匹配时为 {@code true}
     */
    public static Boolean matches(String encodedPassword, String rawPassword) {
        if (encodedPassword == null || rawPassword == null) {
            return false;
        }
        if(!encodedPassword.contains("@")){
            throw new RuntimeException("密码格式不正确！");
        }
        String[] arr = encodedPassword.split("@");
        String salt = arr[0];
        return encodedPassword.equals(encode(rawPassword, salt));
    }
}
