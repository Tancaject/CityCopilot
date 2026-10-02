package com.citycopilot.core.dto;

import lombok.Data;

import java.util.List;

/** 使用最小时间戳与同分偏移量实现滚动分页的返回对象。 */
@Data
public class ScrollResult {
    /** 当前批次数据。 */
    private List<?> list;
    /** 当前批次最小时间戳，作为下一页游标。 */
    private Long minTime;
    /** 与最小时间戳相同记录的累计偏移量。 */
    private Integer offset;
}
