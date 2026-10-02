package com.citycopilot.core.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/** 地理编码批处理任务返回给管理端的进度快照。 */
@Data
@Accessors(chain = true)
public class GeoBatchJobDTO {
    /** 数据库主键。 */
    private Long id;
    /** 对外稳定任务标识。 */
    private String jobId;
    /** 任务当前状态。 */
    private String status;
    /** POI 搜索限定城市。 */
    private String city;
    /** POI 搜索关键词。 */
    private String keyword;
    /** 写回坐标后是否直接标记为已核验。 */
    private Boolean verified;
    /** 是否只处理缺少坐标的店铺。 */
    private Boolean onlyMissing;
    /** 本次任务最大处理条数。 */
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
    /** 发起任务的运营用户名称。 */
    private String operatorName;
    /** 任务开始时间的接口字符串。 */
    private String startedAt;
    /** 任务完成时间的接口字符串。 */
    private String finishedAt;
}
