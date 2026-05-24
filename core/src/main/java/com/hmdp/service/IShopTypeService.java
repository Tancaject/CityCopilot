package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.citycopilot.core.agent.AgentTool;
import com.hmdp.entity.ShopType;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopTypeService extends IService<ShopType> {

    @AgentTool(name = "list_shop_types", description = "List supported local-life shop categories")
    List<ShopType> queryShopType();
}
