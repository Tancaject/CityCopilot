package com.citycopilot.core.controller;

import com.citycopilot.core.dto.Result;
import com.citycopilot.contract.security.PermissionCodes;
import com.citycopilot.core.security.RequirePermission;
import com.citycopilot.core.service.IVoucherOrderService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 运营端订单详情查询接口；整类要求订单写权限。 */
@RestController
@RequestMapping("/admin/orders")
@RequirePermission(PermissionCodes.ORDER_WRITE)
public class AdminOrderController {

    /** 通过 Commerce 端口查询和流转订单状态的服务。 */
    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * @param orderId 订单主键
     * @return Commerce 返回的运营端订单详情
     */
    @GetMapping("/{id}")
    public Result queryOrder(@PathVariable("id") Long orderId) {
        return voucherOrderService.queryOrderForAdmin(orderId);
    }
}
