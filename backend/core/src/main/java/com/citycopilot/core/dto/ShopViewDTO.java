package com.citycopilot.core.dto;

import java.time.LocalDateTime;

/** 店铺读取的既有字段视图；距离单位为米，定位与审计字段保持原有可空语义。 */
public record ShopViewDTO(Long id, String name, Long typeId, Long ownerUserId,
                          String images, String area, String address, Double x, Double y,
                          String poiId, String adcode, String geoSource, String geoStatus,
                          LocalDateTime geoUpdatedAt, Long avgPrice, Integer sold,
                          Integer comments, Integer score, String openHours,
                          LocalDateTime createTime, LocalDateTime updateTime,
                          Long projectionVersion, Double distance) {}
