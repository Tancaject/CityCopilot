package com.citycopilot.core.service;

import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.VoucherRequest;

/**
 * 优惠券兼容服务接口。
 *
 * <p>券及秒杀活动由 Commerce 服务统一写入，Core 只负责鉴权、参数适配和结果转发。</p>
 */
public interface IVoucherService {

    /**
     * 查询店铺面向用户的可用券。
     *
     * @param shopId 店铺主键
     * @return 可用券列表
     */
    Result queryVoucherOfShop(Long shopId);

    /**
     * 查询后台券列表。
     *
     * @param current 兼容旧接口的页码；当前 Commerce 端口返回完整过滤列表
     * @param shopId 可选店铺过滤条件
     * @param keyword 可选名称关键词
     * @param status 可选状态编码
     * @return 过滤后的券列表
     */
    Result queryAdminVouchers(Integer current, Long shopId, String keyword, Integer status);

    /**
     * 创建优惠券。
     *
     * @param voucher 新券资料及可选秒杀配置
     * @return 创建结果
     */
    Result createVoucher(VoucherRequest voucher);

    /**
     * 更新优惠券。
     *
     * @param voucher 含券主键的更新资料
     * @return 更新结果
     */
    Result updateVoucher(VoucherRequest voucher);

    /**
     * 更新优惠券状态。
     *
     * @param id 券主键
     * @param status 目标状态编码
     * @return 更新结果
     */
    Result updateVoucherStatus(Long id, Integer status);
}
