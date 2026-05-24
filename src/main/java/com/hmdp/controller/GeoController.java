package com.hmdp.controller;

import cn.hutool.core.util.StrUtil;
import com.hmdp.config.GeoProperties;
import com.hmdp.dto.AmapPoiDTO;
import com.hmdp.dto.GeoPointDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.ShopGeoUpdateDTO;
import com.hmdp.entity.Shop;
import com.hmdp.security.PermissionCodes;
import com.hmdp.security.RequirePermission;
import com.hmdp.service.AmapGeoService;
import com.hmdp.service.IShopService;
import com.hmdp.service.OperationLogService;
import com.hmdp.service.ShopGeoBatchService;
import com.hmdp.service.ShopGeoIndexService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/geo")
@RequirePermission(PermissionCodes.GEO_READ)
public class GeoController {

    @Resource
    private AmapGeoService amapGeoService;

    @Resource
    private IShopService shopService;

    @Resource
    private ShopGeoIndexService shopGeoIndexService;

    @Resource
    private ShopGeoBatchService shopGeoBatchService;

    @Resource
    private OperationLogService operationLogService;

    @Resource
    private GeoProperties geoProperties;

    @GetMapping("/geocode")
    public Result geocode(
            @RequestParam("address") String address,
            @RequestParam(value = "city", required = false) String city
    ) {
        return amapGeoService.geocode(address, city);
    }

    @PostMapping("/shops/{id}/geocode")
    @RequirePermission(PermissionCodes.GEO_WRITE)
    public Result geocodeShop(
            @PathVariable("id") Long id,
            @RequestParam(value = "city", required = false) String city,
            @RequestParam(value = "address", required = false) String address,
            @RequestParam(value = "verified", defaultValue = "false") Boolean verified
    ) {
        String scopeCity = geoProperties.resolveCity(city);
        Shop shop = shopService.getById(id);
        if (shop == null) {
            return Result.fail("SHOP_NOT_FOUND", "店铺不存在");
        }

        String targetAddress = StrUtil.blankToDefault(address, buildShopAddress(shop, scopeCity));
        Result geocode = amapGeoService.geocode(targetAddress, scopeCity);
        if (!Boolean.TRUE.equals(geocode.getSuccess())) {
            return geocode;
        }

        GeoPointDTO point = (GeoPointDTO) geocode.getData();
        ShopGeoUpdateDTO update = new ShopGeoUpdateDTO();
        update.setId(id);
        update.setX(point.getX());
        update.setY(point.getY());
        update.setAdcode(point.getAdcode());
        update.setGeoSource("amap_geocode");
        update.setGeoStatus(Boolean.TRUE.equals(verified) ? "verified" : "pending");
        return shopService.updateShopGeo(update);
    }

    @GetMapping("/shops/{id}/pois")
    public Result searchShopPois(
            @PathVariable("id") Long id,
            @RequestParam(value = "city", required = false) String city,
            @RequestParam(value = "keyword", required = false) String keyword
    ) {
        String scopeCity = geoProperties.resolveCity(city);
        Shop shop = shopService.getById(id);
        if (shop == null) {
            return Result.fail("SHOP_NOT_FOUND", "店铺不存在");
        }
        return amapGeoService.searchPoi(StrUtil.blankToDefault(keyword, shop.getName()), scopeCity, shop.getAdcode());
    }

    @PostMapping("/shops/{id}/poi")
    @RequirePermission(PermissionCodes.GEO_WRITE)
    public Result confirmShopPoi(
            @PathVariable("id") Long id,
            @RequestParam("poiId") String poiId,
            @RequestParam(value = "city", required = false) String city,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "verified", defaultValue = "true") Boolean verified
    ) {
        String scopeCity = geoProperties.resolveCity(city);
        Shop shop = shopService.getById(id);
        if (shop == null) {
            return Result.fail("SHOP_NOT_FOUND", "店铺不存在");
        }

        Result searchResult = amapGeoService.searchPoi(StrUtil.blankToDefault(keyword, shop.getName()), scopeCity, shop.getAdcode());
        if (!Boolean.TRUE.equals(searchResult.getSuccess())) {
            return searchResult;
        }
        AmapPoiDTO poi = findPoi(searchResult.getData(), poiId);
        if (poi == null) {
            return Result.fail("AMAP_POI_NOT_FOUND", "没有找到匹配的 POI");
        }

        ShopGeoUpdateDTO update = new ShopGeoUpdateDTO();
        update.setId(id);
        update.setX(poi.getX());
        update.setY(poi.getY());
        update.setPoiId(poi.getId());
        update.setAdcode(poi.getAdcode());
        update.setGeoSource("amap_poi");
        update.setGeoStatus(Boolean.TRUE.equals(verified) ? "verified" : "pending");
        return shopService.updateShopGeo(update);
    }

    @PostMapping("/shop-index/sync")
    @RequirePermission(PermissionCodes.GEO_WRITE)
    public Result syncShopGeoIndex() {
        Integer count = shopGeoIndexService.syncShopGeoIndex();
        operationLogService.record("geo", "geo_index_sync", "redis_geo", null, "shop_geo", null, count, true, null);
        return Result.ok(count);
    }

    @GetMapping("/amap/status")
    public Result getAmapStatus() {
        return Result.ok(amapGeoService.getStatus());
    }

    @PostMapping("/shops/batch-geocode")
    @RequirePermission(PermissionCodes.GEO_WRITE)
    public Result startBatchGeocode(
            @RequestParam(value = "city", required = false) String city,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "verified", defaultValue = "false") Boolean verified,
            @RequestParam(value = "onlyMissing", defaultValue = "true") Boolean onlyMissing,
            @RequestParam(value = "limit", defaultValue = "20") Integer limit
    ) {
        return Result.ok(shopGeoBatchService.startGeocodeJob(geoProperties.resolveCity(city), keyword, verified, onlyMissing, limit));
    }

    @GetMapping("/batch-jobs")
    public Result queryBatchJobs(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "status", required = false) String status
    ) {
        return Result.ok(shopGeoBatchService.queryJobs(current, status));
    }

    @GetMapping("/batch-jobs/{jobId}")
    public Result getBatchJob(@PathVariable("jobId") String jobId) {
        Object job = shopGeoBatchService.getJob(jobId);
        if (job == null) {
            return Result.fail("GEO_BATCH_NOT_FOUND", "批量任务不存在或已过期");
        }
        return Result.ok(job);
    }

    @PostMapping("/batch-jobs/{jobId}/cancel")
    @RequirePermission(PermissionCodes.GEO_WRITE)
    public Result cancelBatchJob(@PathVariable("jobId") String jobId) {
        return shopGeoBatchService.cancelJob(jobId);
    }

    @PostMapping("/batch-jobs/{jobId}/retry")
    @RequirePermission(PermissionCodes.GEO_WRITE)
    public Result retryBatchJob(
            @PathVariable("jobId") String jobId,
            @RequestParam(value = "verified", required = false) Boolean verified,
            @RequestParam(value = "limit", defaultValue = "20") Integer limit
    ) {
        return shopGeoBatchService.retryFailedItems(jobId, verified, limit);
    }

    @GetMapping("/batch-jobs/{jobId}/items")
    public Result getBatchJobItems(@PathVariable("jobId") String jobId) {
        if (shopGeoBatchService.getJob(jobId) == null) {
            return Result.fail("GEO_BATCH_NOT_FOUND", "批量任务不存在");
        }
        return Result.ok(shopGeoBatchService.getJobItems(jobId));
    }

    @SuppressWarnings("unchecked")
    private AmapPoiDTO findPoi(Object data, String poiId) {
        if (!(data instanceof List<?> pois)) {
            return null;
        }
        return pois.stream()
                .filter(AmapPoiDTO.class::isInstance)
                .map(AmapPoiDTO.class::cast)
                .filter(poi -> poiId.equals(poi.getId()))
                .findFirst()
                .orElse(null);
    }

    private String buildShopAddress(Shop shop, String city) {
        StringBuilder builder = new StringBuilder();
        if (StrUtil.isNotBlank(city)) {
            builder.append(city);
        }
        if (StrUtil.isNotBlank(shop.getArea()) && !builder.toString().contains(shop.getArea())) {
            builder.append(shop.getArea());
        }
        if (StrUtil.isNotBlank(shop.getAddress())) {
            builder.append(shop.getAddress());
        }
        return builder.toString();
    }
}
