package com.citycopilot.core.dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class AmapPoiDTO {
    private String id;
    private String name;
    private String type;
    private String typecode;
    private String address;
    private String tel;
    private String businessArea;
    private String tag;
    private String provinceName;
    private String cityName;
    private String adName;
    private String adcode;
    private Double x;
    private Double y;
    private Double distance;
    private String images;
    private Long avgPrice;
    private Integer score;
}
