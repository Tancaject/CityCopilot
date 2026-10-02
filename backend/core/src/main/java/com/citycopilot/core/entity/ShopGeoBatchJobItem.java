package com.citycopilot.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 对应 {@code geo_batch_job_item} 表的单店地理编码处理明细。 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("geo_batch_job_item")
public class ShopGeoBatchJobItem implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 明细记录主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属批处理任务标识。 */
    private String jobId;

    /** 被处理店铺主键。 */
    private Long shopId;

    /** 被处理店铺名称快照。 */
    private String shopName;

    /** 单店处理状态。 */
    private String status;

    /** 成功说明或失败原因。 */
    private String message;

    /** 更新前店铺地理信息 JSON。 */
    private String beforeData;

    /** 更新后店铺地理信息 JSON。 */
    private String afterData;

    /** 明细创建时间。 */
    private LocalDateTime createTime;

    /** 明细最近更新时间。 */
    private LocalDateTime updateTime;
}
