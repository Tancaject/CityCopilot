package com.citycopilot.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "citycopilot.amap")
public class AmapProperties {
    private String webServiceKey;
    private String geocodeUrl = "https://restapi.amap.com/v3/geocode/geo";
    private String poiTextUrl = "https://restapi.amap.com/v3/place/text";
    private String poiAroundUrl = "https://restapi.amap.com/v3/place/around";
    private Integer cacheTtlMinutes = 1440;
    private Integer rateLimitPerMinute = 60;
    private Integer circuitFailureThreshold = 5;
    private Integer circuitOpenSeconds = 120;
    private Integer metricsTtlDays = 7;
}
