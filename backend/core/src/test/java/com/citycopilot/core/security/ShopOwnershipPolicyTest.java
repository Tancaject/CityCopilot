package com.citycopilot.core.security;

import com.citycopilot.core.dto.UserDTO;
import com.citycopilot.core.entity.Shop;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShopOwnershipPolicyTest {
    private final ShopOwnershipPolicy policy = new ShopOwnershipPolicy();

    @Test
    void merchantCanOnlyWriteOwnedShop() {
        UserDTO merchant = user(10L, "merchant");
        assertDoesNotThrow(() -> policy.assertWritable(new Shop().setOwnerUserId(10L), merchant));
        assertThrows(
                ShopAccessDeniedException.class,
                () -> policy.assertWritable(new Shop().setOwnerUserId(11L), merchant)
        );
    }

    @Test
    void adminIsExemptAndMerchantCreateGetsOwner() {
        assertDoesNotThrow(() -> policy.assertWritable(new Shop().setOwnerUserId(11L), user(1L, "admin")));
        Shop shop = new Shop();
        policy.assignOwnerOnCreate(shop, user(10L, "merchant"));
        assertEquals(10L, shop.getOwnerUserId());
    }

    private UserDTO user(Long id, String role) {
        UserDTO user = new UserDTO();
        user.setId(id);
        user.setRole(role);
        return user;
    }
}
