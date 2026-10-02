package com.citycopilot.core.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.citycopilot.core.dto.ShopTypeDTO;
import com.citycopilot.core.entity.ShopType;
import com.citycopilot.core.mapper.ShopTypeMapper;
import com.citycopilot.core.service.IShopTypeService;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.citycopilot.core.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * 店铺分类查询实现，使用 Cache Aside 降低分类字典的数据库读取压力。
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    /** Redis 客户端，用于读取和回填分类列表缓存。 */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public ShopTypeDTO findShopType(Long typeId) {
        ShopType type = getById(typeId);
        return type == null ? null : new ShopTypeDTO(type.getId(), type.getName(), type.getIcon(), type.getSort());
    }

    /**
     * 查询按展示顺序排列的全部分类。
     *
     * @return 缓存或数据库中的分类列表
     */
    @Override
    public List<ShopTypeDTO> queryShopType() {
        // Cache Aside：命中时直接反序列化，未命中才访问数据库并回填。
        String json = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);
        List<ShopType> typeList;
        if (StrUtil.isNotBlank(json)) {
            typeList = JSONUtil.toList(json, ShopType.class);
        } else {
            typeList = query().orderByAsc("sort").list();
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_KEY, JSONUtil.toJsonStr(typeList));
        }
        return typeList.stream().map(type -> new ShopTypeDTO(
                type.getId(), type.getName(), type.getIcon(), type.getSort())).toList();
    }
}
