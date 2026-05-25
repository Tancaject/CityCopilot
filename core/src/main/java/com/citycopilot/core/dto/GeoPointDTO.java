package com.citycopilot.core.dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class GeoPointDTO {
    private Double x;
    private Double y;
    private String formattedAddress;
    private String province;
    private String city;
    private String district;
    private String adcode;
}
