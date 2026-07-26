package com.citycopilot.core.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.citycopilot.contract.tool.AgentTool;
import com.citycopilot.core.entity.ShopType;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IShopTypeService extends IService<ShopType> {

    @AgentTool(name = "list_shop_types", description = "List supported local-life shop categories")
    List<ShopType> queryShopType();
}
