package com.citycopilot.core.security;

import com.citycopilot.core.dto.UserDTO;
import com.citycopilot.core.entity.Shop;
import org.springframework.stereotype.Component;

/**
 * 商户店铺归属策略。
 *
 * <p>管理员等非 merchant 角色由通用权限体系控制；merchant 创建店铺时自动成为 owner，
 * 后续只能修改自己名下的店铺。</p>
 */
@Component
public class ShopOwnershipPolicy {

    /**
     * @param shop 新建店铺实体
     * @param user 当前登录用户
     */
    public void assignOwnerOnCreate(Shop shop, UserDTO user) {
        if (shop != null && isMerchant(user)) {
            shop.setOwnerUserId(user.getId());
        }
    }

    /**
     * 校验商户是否拥有待修改店铺；非商户角色不在本策略内限制。
     *
     * @param shop 待修改的现有店铺
     * @param user 当前登录用户
     * @throws ShopAccessDeniedException 商户不是店铺 owner 时抛出
     */
    public void assertWritable(Shop shop, UserDTO user) {
        if (!isMerchant(user)) {
            return;
        }
        if (shop == null || user.getId() == null || !user.getId().equals(shop.getOwnerUserId())) {
            throw new ShopAccessDeniedException("商户只能修改自己名下的店铺");
        }
    }

    /** @return 当前用户是否为 merchant 角色 */
    private boolean isMerchant(UserDTO user) {
        return user != null && "merchant".equals(user.getRole());
    }
}
