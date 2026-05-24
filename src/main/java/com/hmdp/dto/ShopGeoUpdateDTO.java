package com.hmdp.dto;

import lombok.Data;

@Data
public class ShopGeoUpdateDTO {
    private Long id;
    private Double x;
    private Double y;
    private String poiId;
    private String adcode;
    private String geoSource;
    private String geoStatus;
}
