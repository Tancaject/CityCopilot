package com.citycopilot.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.citycopilot.core.entity.Shop;

import java.util.List;

/** 通过 MyBatis-Plus 访问 Core 权威店铺表。 */
public interface ShopMapper extends BaseMapper<Shop> {
    /** 固定基础扫描上界，重建期间的新写入由 Outbox 水位追平。 */
    default long projectionHighWatermark() {
        List<Shop> last = selectList(Wrappers.<Shop>lambdaQuery()
                .select(Shop::getId).orderByDesc(Shop::getId).last("LIMIT 1"));
        return last.isEmpty() ? 0L : last.get(0).getId();
    }

    default List<Shop> projectionPage(long exclusiveId, long inclusiveId) {
        return selectList(Wrappers.<Shop>lambdaQuery()
                .gt(Shop::getId, exclusiveId).le(Shop::getId, inclusiveId)
                .orderByAsc(Shop::getId).last("LIMIT 500"));
    }
}
