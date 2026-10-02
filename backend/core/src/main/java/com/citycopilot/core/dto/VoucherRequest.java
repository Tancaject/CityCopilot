package com.citycopilot.core.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * Core 转发给 Commerce 优惠券 API 的 HTTP 命令对象。
 *
 * <p>该类型刻意不带 MyBatis 元数据：优惠券由 Commerce 独占写入，Core 不直接持久化。</p>
 */
@Data
@Accessors(chain = true)
public class VoucherRequest {
    /** 更新场景中的优惠券主键。 */
    private Long id;
    /** 归属店铺主键。 */
    private Long shopId;
    /** 优惠券标题。 */
    private String title;
    /** 优惠券副标题。 */
    private String subTitle;
    /** 使用规则说明。 */
    private String rules;
    /** 达到优惠条件所需金额。 */
    private Long payValue;
    /** 实际抵扣后支付金额。 */
    private Long actualValue;
    /** 优惠券类型码。 */
    private Integer type;
    /** 上架或生效状态码。 */
    private Integer status;
    /** 可售库存。 */
    private Integer stock;

    /** 活动开始时间。 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime beginTime;

    /** 活动结束时间。 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;
}
