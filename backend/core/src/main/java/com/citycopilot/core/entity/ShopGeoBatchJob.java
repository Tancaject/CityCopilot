package com.citycopilot.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 对应 {@code geo_batch_job} 表的批量地理编码任务及汇总进度。 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("geo_batch_job")
public class ShopGeoBatchJob implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 任务记录主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 对外稳定任务标识，用于关联明细项。 */
    private String jobId;

    /** queued、running、finished、canceled 等任务状态。 */
    private String status;

    /** 高德搜索限定城市。 */
    private String city;

    /** 高德搜索关键词。 */
    private String keyword;

    /** 写回坐标后是否直接标记为已核验。 */
    private Boolean verified;

    /** 是否只处理缺少坐标的店铺。 */
    private Boolean onlyMissing;

    /** 本任务最大处理条数。 */
    private Integer limitSize;

    /** 任务总条数。 */
    private Integer total;

    /** 已处理条数。 */
    private Integer processed;

    /** 成功条数。 */
    private Integer success;

    /** 失败条数。 */
    private Integer failed;

    /** 当前进度或终止原因说明。 */
    private String message;

    /** 发起任务的运营用户主键。 */
    private Long operatorId;

    /** 发起任务的运营用户名称快照。 */
    private String operatorName;

    /** 实际开始处理时间。 */
    private LocalDateTime startedAt;

    /** 任务结束时间。 */
    private LocalDateTime finishedAt;

    /** 记录创建时间。 */
    private LocalDateTime createTime;

    /** 任务进度最近更新时间。 */
    private LocalDateTime updateTime;
}
