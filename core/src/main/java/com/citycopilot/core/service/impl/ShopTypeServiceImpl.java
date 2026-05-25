package com.citycopilot.core.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.citycopilot.core.entity.ShopType;
import com.citycopilot.core.mapper.ShopTypeMapper;
import com.citycopilot.core.service.IShopTypeService;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.citycopilot.core.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ShopType> queryShopType() {
        // 1. 查 Redis 缓存
        String json = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);
        // 2. 缓存存在，直接返回
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toList(json, ShopType.class);
        }
        // 3. 缓存不存在，查数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        // 4. 写入 Redis 缓存
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_KEY, JSONUtil.toJsonStr(typeList));
        // 5. 返回
        return typeList;
    }
}
