package com.citycopilot.core.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ShopDiscoveryItemDTO {
    private Long id;
    private String source;
    private String sourceId;
    private boolean orderable;
    private Long typeId;
    private String typeName;
    private String name;
    private String images;
    private String area;
    private String address;
    private String tel;
    private String businessArea;
    private String tag;
    private Double x;
    private Double y;
    private String poiId;
    private String adcode;
    private String geoSource;
    private String geoStatus;
    private Long avgPrice;
    private Integer sold;
    private Integer comments;
    private Integer score;
    private String openHours;
    private Double distance;
    @JsonIgnore
    private Integer searchRank;
}
