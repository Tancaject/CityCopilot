package com.citycopilot.core.controller;

import com.citycopilot.core.dto.ShopViewDTO;
import com.citycopilot.core.service.IShopService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 向 Commerce 等可信内部服务提供店铺存在性与归属信息。
 *
 * <p>路由受内部服务令牌保护；Commerce 用该权威结果校验优惠券关联店铺及其 owner。</p>
 */
@RestController
@RequestMapping("/internal/v1/place")
public class InternalPlaceAuthorityController {
    /** 读取 Core 权威店铺表的服务。 */
    private final IShopService shops;

    /** @param shops 店铺业务服务 */
    public InternalPlaceAuthorityController(IShopService shops) {
        this.shops = shops;
    }

    /**
     * @param shopId 待核验店铺主键
     * @return exists、店铺名和 ownerUserId；未分配 owner 时使用 -1
     */
    @GetMapping("/shops/{shopId}/authority")
    public Map<String, Object> authority(@PathVariable long shopId) {
        ShopViewDTO shop = shops.findAuthoritativeShop(shopId);
        return shop == null
                ? Map.of("exists", false, "shopId", shopId)
                : Map.of(
                        "exists", true,
                        "shopId", shopId,
                        "name", shop.name(),
                        "ownerUserId", shop.ownerUserId() == null ? -1L : shop.ownerUserId()
                );
    }
}
