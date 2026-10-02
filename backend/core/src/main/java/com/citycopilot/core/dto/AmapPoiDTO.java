package com.citycopilot.core.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/** 高德 POI 搜索结果在 Core 内部使用的传输对象。 */
@Data
@Accessors(chain = true)
public class AmapPoiDTO {
    /** 高德 POI 唯一标识。 */
    private String id;
    /** POI 名称。 */
    private String name;
    /** 高德返回的分类文本。 */
    private String type;
    /** 高德分类编码。 */
    private String typecode;
    /** POI 详细地址。 */
    private String address;
    /** 联系电话。 */
    private String tel;
    /** 所属商圈。 */
    private String businessArea;
    /** 业务标签。 */
    private String tag;
    /** 省级行政区名称。 */
    private String provinceName;
    /** 城市名称。 */
    private String cityName;
    /** 区县名称。 */
    private String adName;
    /** 行政区划编码。 */
    private String adcode;
    /** 经度。 */
    private Double x;
    /** 纬度。 */
    private Double y;
    /** 相对查询中心的距离，单位米。 */
    private Double distance;
    /** 高德照片列表中的第一张图片地址。 */
    private String images;
    /** 人均价格。 */
    private Long avgPrice;
    /** 评分。 */
    private Integer score;
}
