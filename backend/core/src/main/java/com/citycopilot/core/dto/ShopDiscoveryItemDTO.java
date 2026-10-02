package com.citycopilot.core.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.experimental.Accessors;

/** 统一承载本地 MySQL 与高德 POI 两种来源的店铺发现结果。 */
@Data
@Accessors(chain = true)
public class ShopDiscoveryItemDTO {
    /** 本地店铺主键；外部 POI 可能为空。 */
    private Long id;
    /** 数据来源标识，例如 local 或 amap。 */
    private String source;
    /** 来源系统内的唯一标识。 */
    private String sourceId;
    /** 是否可进入 CityCopilot 下单链路。 */
    private boolean orderable;
    /** 本地店铺分类主键。 */
    private Long typeId;
    /** 店铺分类名称。 */
    private String typeName;
    /** 店铺或 POI 名称。 */
    private String name;
    /** 图片地址。 */
    private String images;
    /** 所在区域。 */
    private String area;
    /** 详细地址。 */
    private String address;
    /** 联系电话。 */
    private String tel;
    /** 所属商圈。 */
    private String businessArea;
    /** 业务标签。 */
    private String tag;
    /** 经度。 */
    private Double x;
    /** 纬度。 */
    private Double y;
    /** 外部 POI 标识。 */
    private String poiId;
    /** 行政区划编码。 */
    private String adcode;
    /** 坐标来源。 */
    private String geoSource;
    /** 地理信息处理状态。 */
    private String geoStatus;
    /** 人均价格。 */
    private Long avgPrice;
    /** 销量。 */
    private Integer sold;
    /** 评论数。 */
    private Integer comments;
    /** 评分。 */
    private Integer score;
    /** 营业时间。 */
    private String openHours;
    /** 到查询中心的距离，单位米。 */
    private Double distance;
    /** 内部合并排序位次，不序列化给客户端。 */
    @JsonIgnore
    private Integer searchRank;
}
