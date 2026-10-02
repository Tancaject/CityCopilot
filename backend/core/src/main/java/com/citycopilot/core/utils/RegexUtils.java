package com.citycopilot.core.utils;

import cn.hutool.core.util.StrUtil;

/** 为常见账户字段提供“是否无效”的空值与正则组合校验。 */
public class RegexUtils {
    /**
     * 是否是无效手机格式
     * @param phone 要校验的手机号
     * @return 空白或不符合手机号正则时为 {@code true}
     */
    public static boolean isPhoneInvalid(String phone){
        return mismatch(phone, RegexPatterns.PHONE_REGEX);
    }
    /**
     * 是否是无效邮箱格式
     * @param email 要校验的邮箱
     * @return 空白或不符合邮箱正则时为 {@code true}
     */
    public static boolean isEmailInvalid(String email){
        return mismatch(email, RegexPatterns.EMAIL_REGEX);
    }

    /**
     * 是否是无效验证码格式
     * @param code 要校验的验证码
     * @return 空白或不符合验证码正则时为 {@code true}
     */
    public static boolean isCodeInvalid(String code){
        return mismatch(code, RegexPatterns.VERIFY_CODE_REGEX);
    }

    /** @return 文本为空或不匹配给定正则时为 {@code true} */
    private static boolean mismatch(String str, String regex){
        if (StrUtil.isBlank(str)) {
            return true;
        }
        return !str.matches(regex);
    }
}
