package com.citycopilot.core.controller;


import com.citycopilot.core.dto.Result;
import com.citycopilot.core.service.IShopTypeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

/** 向前端提供按展示顺序排列的店铺分类列表。 */
@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {
    /** 查询店铺分类并管理其缓存的业务服务。 */
    @Resource
    private IShopTypeService typeService;

    /** @return 全部店铺分类的统一成功响应 */
    @GetMapping("list")
    public Result queryTypeList() {
        return Result.ok(typeService.queryShopType());
    }
}
