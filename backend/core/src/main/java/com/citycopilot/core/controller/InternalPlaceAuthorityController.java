package com.citycopilot.core.controller;

import com.citycopilot.core.entity.Shop;
import com.citycopilot.core.service.IShopService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/internal/v1/place")
public class InternalPlaceAuthorityController {
    private final IShopService shops;

    public InternalPlaceAuthorityController(IShopService shops) {
        this.shops = shops;
    }

    @GetMapping("/shops/{shopId}/authority")
    public Map<String, Object> authority(@PathVariable long shopId) {
        Shop shop = shops.getById(shopId);
        return shop == null
                ? Map.of("exists", false, "shopId", shopId)
                : Map.of(
                        "exists", true,
                        "shopId", shopId,
                        "name", shop.getName(),
                        "ownerUserId", shop.getOwnerUserId() == null ? -1L : shop.getOwnerUserId()
                );
    }
}
