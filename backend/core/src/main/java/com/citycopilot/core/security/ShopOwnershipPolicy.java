package com.citycopilot.core.security;

import com.citycopilot.core.dto.UserDTO;
import com.citycopilot.core.entity.Shop;
import org.springframework.stereotype.Component;

@Component
public class ShopOwnershipPolicy {

    public void assignOwnerOnCreate(Shop shop, UserDTO user) {
        if (shop != null && isMerchant(user)) {
            shop.setOwnerUserId(user.getId());
        }
    }

    public void assertWritable(Shop shop, UserDTO user) {
        if (!isMerchant(user)) {
            return;
        }
        if (shop == null || user.getId() == null || !user.getId().equals(shop.getOwnerUserId())) {
            throw new ShopAccessDeniedException("商户只能修改自己名下的店铺");
        }
    }

    private boolean isMerchant(UserDTO user) {
        return user != null && "merchant".equals(user.getRole());
    }
}
