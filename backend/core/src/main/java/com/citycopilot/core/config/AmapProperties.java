package com.citycopilot.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 绑定 {@code citycopilot.amap}，管理高德 Web Service 地址、缓存、限流与熔断参数。 */
@Data
@Component
@ConfigurationProperties(prefix = "citycopilot.amap")
public class AmapProperties {
    /** 高德 Web Service API Key。 */
    private String webServiceKey;
    /** 地址地理编码接口地址。 */
    private String geocodeUrl = "https://restapi.amap.com/v3/geocode/geo";
    /** 关键词 POI 搜索接口地址。 */
    private String poiTextUrl = "https://restapi.amap.com/v3/place/text";
    /** 周边 POI 搜索接口地址。 */
    private String poiAroundUrl = "https://restapi.amap.com/v3/place/around";
    /** 成功响应缓存时间，单位分钟。 */
    private Integer cacheTtlMinutes = 1440;
    /** 分布式分钟调用上限。 */
    private Integer rateLimitPerMinute = 60;
    /** 连续失败达到该值后打开熔断器。 */
    private Integer circuitFailureThreshold = 5;
    /** 熔断打开持续时间，单位秒。 */
    private Integer circuitOpenSeconds = 120;
    /** 调用指标保留时间，单位天。 */
    private Integer metricsTtlDays = 7;
}
