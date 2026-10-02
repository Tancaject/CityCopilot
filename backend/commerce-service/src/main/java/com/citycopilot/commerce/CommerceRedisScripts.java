package com.citycopilot.commerce;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** 集中加载 Commerce Lua 脚本，统一声明脚本返回值为 {@link Long}。 */
final class CommerceRedisScripts {

    private CommerceRedisScripts() {
    }

    /**
     * 从 classpath 创建一个返回整数状态码的 Redis 脚本描述对象。
     *
     * @param path Lua 脚本的 classpath 路径
     * @return 可交给 {@code StringRedisTemplate.execute} 的脚本对象
     */
    static DefaultRedisScript<Long> longScript(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }
}
