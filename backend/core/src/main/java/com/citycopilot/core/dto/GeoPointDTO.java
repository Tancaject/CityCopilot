package com.citycopilot.core.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/** 高德地理编码返回的坐标及行政区信息。 */
@Data
@Accessors(chain = true)
public class GeoPointDTO {
    /** 经度。 */
    private Double x;
    /** 纬度。 */
    private Double y;
    /** 标准化完整地址。 */
    private String formattedAddress;
    /** 省级行政区。 */
    private String province;
    /** 城市。 */
    private String city;
    /** 区县。 */
    private String district;
    /** 行政区划编码。 */
    private String adcode;
}
