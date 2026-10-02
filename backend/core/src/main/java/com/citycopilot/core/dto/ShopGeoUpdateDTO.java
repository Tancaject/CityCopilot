package com.citycopilot.core.dto;

import lombok.Data;

/** 管理端更新单个店铺地理信息的命令对象。 */
@Data
public class ShopGeoUpdateDTO {
    /** 待更新店铺主键。 */
    private Long id;
    /** 经度。 */
    private Double x;
    /** 纬度。 */
    private Double y;
    /** 匹配到的外部 POI 标识。 */
    private String poiId;
    /** 行政区划编码。 */
    private String adcode;
    /** 坐标来源，例如 amap 或 manual。 */
    private String geoSource;
    /** 地理信息处理状态。 */
    private String geoStatus;
}
