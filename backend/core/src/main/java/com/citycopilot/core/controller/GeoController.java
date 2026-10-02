package com.citycopilot.core.controller;

import cn.hutool.core.util.StrUtil;
import com.citycopilot.core.config.GeoProperties;
import com.citycopilot.core.dto.AmapPoiDTO;
import com.citycopilot.core.dto.GeoPointDTO;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.ShopGeoUpdateDTO;
import com.citycopilot.core.dto.ShopViewDTO;
import com.citycopilot.core.security.PermissionCodes;
import com.citycopilot.core.security.RequirePermission;
import com.citycopilot.core.service.AmapGeoService;
import com.citycopilot.core.service.IShopService;
import com.citycopilot.core.service.OperationLogService;
import com.citycopilot.core.service.ShopGeoBatchService;
import com.citycopilot.core.service.ShopGeoIndexService;
import com.citycopilot.core.projection.ShopProjectionGenerationCleanup;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.List;

/**
 * 店铺地理数据维护与高德接入控制器。
 *
 * <p>类级权限允许 GEO 只读操作，坐标落库、索引重建和批任务变更另需 GEO 写权限。
 * 高德只提供地址/POI 候选，最终店铺坐标仍通过店铺服务校验、审计并写入本地事实库。
 * 只读投影角色可通过配置关闭本控制器。</p>
 */
@RestController
@RequestMapping("/geo")
@RequirePermission(PermissionCodes.GEO_READ)
@ConditionalOnProperty(
        prefix = "citycopilot.projection.roles",
        name = "api-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class GeoController {

    /** 高德 Web 服务适配器，负责地址解析、POI 搜索及调用状态。 */
    @Resource
    private AmapGeoService amapGeoService;

    /** 店铺事实源服务，负责读取店铺和持久化 GEO 变更。 */
    @Resource
    private IShopService shopService;

    /** Redis GEO 店铺索引构建服务。 */
    @Resource
    private ShopGeoIndexService shopGeoIndexService;

    @Resource
    private ShopProjectionGenerationCleanup projectionGenerationCleanup;

    /** 地址解析批任务的创建、查询、取消和重试服务。 */
    @Resource
    private ShopGeoBatchService shopGeoBatchService;

    /** GEO 运维动作审计服务。 */
    @Resource
    private OperationLogService operationLogService;

    /** 默认城市、分页和 GEO 运行参数配置。 */
    @Resource
    private GeoProperties geoProperties;

    /**
     * 调用高德将地址解析为坐标候选。
     *
     * @param address 待解析的完整或局部地址
     * @param city 可选城市范围
     * @return 坐标、行政区码等解析结果，或稳定的高德错误
     */
    @GetMapping("/geocode")
    public Result geocode(
            @RequestParam("address") String address,
            @RequestParam(value = "city", required = false) String city
    ) {
        return amapGeoService.geocode(address, city);
    }

    /**
     * 根据店铺地址调用高德解析，并将结果写回店铺事实库。
     *
     * <p>未提供地址时拼接店铺所属区域和地址；自动解析默认标记为 {@code pending}，只有调用方
     * 明确确认后才标记 {@code verified}。</p>
     *
     * @param id 店铺主键
     * @param city 可选城市，空白时使用 GEO 默认城市
     * @param address 可选覆盖地址
     * @param verified 是否将结果直接标为已核验，默认否
     * @return 更新后的店铺，或店铺、高德、坐标校验失败结果
     */
    @PostMapping("/shops/{id}/geocode")
    @RequirePermission(PermissionCodes.GEO_WRITE)
    public Result geocodeShop(
            @PathVariable("id") Long id,
            @RequestParam(value = "city", required = false) String city,
            @RequestParam(value = "address", required = false) String address,
            @RequestParam(value = "verified", defaultValue = "false") Boolean verified
    ) {
        String scopeCity = geoProperties.resolveCity(city);
        ShopViewDTO shop = shopService.findAuthoritativeShop(id);
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
        // 统一走店铺服务，复用坐标/所有权校验、缓存失效、投影发布和操作审计。
        return shopService.updateShopGeo(update);
    }

    /**
     * 为指定店铺搜索可供人工选择的高德 POI 候选。
     *
     * @param id 店铺主键
     * @param city 可选城市，空白时使用默认城市
     * @param keyword 可选搜索词，空白时使用店铺名称
     * @return 高德 POI 候选列表或调用失败结果
     */
    @GetMapping("/shops/{id}/pois")
    public Result searchShopPois(
            @PathVariable("id") Long id,
            @RequestParam(value = "city", required = false) String city,
            @RequestParam(value = "keyword", required = false) String keyword
    ) {
        String scopeCity = geoProperties.resolveCity(city);
        ShopViewDTO shop = shopService.findAuthoritativeShop(id);
        if (shop == null) {
            return Result.fail("SHOP_NOT_FOUND", "店铺不存在");
        }
        return amapGeoService.searchPoi(StrUtil.blankToDefault(keyword, shop.name()), scopeCity, shop.adcode());
    }

    /**
     * 确认一个高德 POI，并把其坐标及标识写回店铺。
     *
     * <p>控制器会重新执行受城市和店铺行政区约束的 POI 搜索，再按 {@code poiId} 精确选择，
     * 不接受调用方直接提交未经高德结果验证的坐标。</p>
     *
     * @param id 店铺主键
     * @param poiId 要确认的高德 POI 标识
     * @param city 可选城市范围
     * @param keyword 可选搜索词，空白时使用店铺名称
     * @param verified 是否标记为已核验，默认是
     * @return 更新后的店铺或 POI 未找到等失败结果
     */
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
        ShopViewDTO shop = shopService.findAuthoritativeShop(id);
        if (shop == null) {
            return Result.fail("SHOP_NOT_FOUND", "店铺不存在");
        }

        Result searchResult = amapGeoService.searchPoi(StrUtil.blankToDefault(keyword, shop.name()), scopeCity, shop.adcode());
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
        // 选择候选后仍通过事实源服务落库，避免直接修改 Redis GEO 投影。
        return shopService.updateShopGeo(update);
    }

    /**
     * 从本地店铺事实数据全量重建 Redis GEO 索引。
     *
     * <p>该运维写操作会记录同步数量，便于审计一次人工重建的影响范围。</p>
     *
     * @return 成功写入索引的店铺数量
     */
    @PostMapping("/shop-index/sync")
    @RequirePermission(PermissionCodes.GEO_WRITE)
    public Result syncShopGeoIndex() {
        Integer count = shopGeoIndexService.syncShopGeoIndex();
        operationLogService.record("geo", "geo_index_sync", "redis_geo", null, "shop_geo", null, count, true, null);
        return Result.ok(count);
    }

    /** 每次请求只回收一批；声明必须来自已实际封闭读入口并排空全部读者的维护操作。 */
    @PostMapping("/shop-index/retired/cleanup")
    @RequirePermission(PermissionCodes.GEO_WRITE)
    public Result cleanupRetiredGenerations(
            @RequestParam(value = "readersQuiesced", defaultValue = "false") boolean readersQuiesced) {
        try {
            var result = projectionGenerationCleanup.cleanup(readersQuiesced);
            operationLogService.record("geo", "projection_generation_cleanup", "shop_projection", null,
                    "retired_generations", null, result, true, null);
            return Result.ok(result);
        } catch (RuntimeException failure) {
            operationLogService.record("geo", "projection_generation_cleanup", "shop_projection", null,
                    "retired_generations", null, null, false, failure.getMessage());
            return Result.fail("PROJECTION_CLEANUP_FAILED", "旧投影代际清理已停止，已完成的删除不会回滚；"
                    + "核对维护窗口、代际引用和候选记录后再重试。原因：" + failure.getMessage());
        }
    }

    /**
     * 查询高德客户端配置、熔断和限流等运行状态。
     *
     * @return 高德集成状态，不触发外部地理查询
     */
    @GetMapping("/amap/status")
    public Result getAmapStatus() {
        return Result.ok(amapGeoService.getStatus());
    }

    /**
     * 创建店铺地址批量解析任务。
     *
     * @param city 可选城市，空白时使用默认城市
     * @param keyword 可选店铺筛选词
     * @param verified 任务写回结果是否直接标为已核验
     * @param onlyMissing 是否只处理缺失坐标的店铺
     * @param limit 本批最多处理的店铺数
     * @return 新建批任务的状态视图
     */
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

    /**
     * 分页查询 GEO 批任务。
     *
     * @param current 页码，默认第 1 页
     * @param status 可选任务状态过滤
     * @return 批任务分页结果
     */
    @GetMapping("/batch-jobs")
    public Result queryBatchJobs(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "status", required = false) String status
    ) {
        return Result.ok(shopGeoBatchService.queryJobs(current, status));
    }

    /**
     * 查询单个 GEO 批任务状态。
     *
     * @param jobId 批任务标识
     * @return 任务状态；不存在或已过期时返回 {@code GEO_BATCH_NOT_FOUND}
     */
    @GetMapping("/batch-jobs/{jobId}")
    public Result getBatchJob(@PathVariable("jobId") String jobId) {
        Object job = shopGeoBatchService.getJob(jobId);
        if (job == null) {
            return Result.fail("GEO_BATCH_NOT_FOUND", "批量任务不存在或已过期");
        }
        return Result.ok(job);
    }

    /**
     * 取消尚可终止的 GEO 批任务。
     *
     * @param jobId 批任务标识
     * @return 取消后的任务状态或状态冲突结果
     */
    @PostMapping("/batch-jobs/{jobId}/cancel")
    @RequirePermission(PermissionCodes.GEO_WRITE)
    public Result cancelBatchJob(@PathVariable("jobId") String jobId) {
        return shopGeoBatchService.cancelJob(jobId);
    }

    /**
     * 为原任务中的失败明细创建重试处理。
     *
     * @param jobId 原批任务标识
     * @param verified 可选覆盖的核验状态
     * @param limit 本次最多重试的失败明细数
     * @return 重试任务或没有可重试明细等失败结果
     */
    @PostMapping("/batch-jobs/{jobId}/retry")
    @RequirePermission(PermissionCodes.GEO_WRITE)
    public Result retryBatchJob(
            @PathVariable("jobId") String jobId,
            @RequestParam(value = "verified", required = false) Boolean verified,
            @RequestParam(value = "limit", defaultValue = "20") Integer limit
    ) {
        return shopGeoBatchService.retryFailedItems(jobId, verified, limit);
    }

    /**
     * 查询批任务的逐店铺处理明细。
     *
     * <p>先确认任务仍存在，避免把已过期任务的空明细误表示为有效空结果。</p>
     *
     * @param jobId 批任务标识
     * @return 任务明细列表或不存在结果
     */
    @GetMapping("/batch-jobs/{jobId}/items")
    public Result getBatchJobItems(@PathVariable("jobId") String jobId) {
        if (shopGeoBatchService.getJob(jobId) == null) {
            return Result.fail("GEO_BATCH_NOT_FOUND", "批量任务不存在");
        }
        return Result.ok(shopGeoBatchService.getJobItems(jobId));
    }

    /**
     * 从高德搜索响应中按标识精确选取 POI。
     *
     * @param data 高德搜索结果载荷
     * @param poiId 目标 POI 标识
     * @return 匹配的 POI；载荷类型异常或未匹配时返回 {@code null}
     */
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

    /**
     * 组合城市、区域和门牌地址，避免区域名称重复拼接。
     *
     * @param shop 店铺资料
     * @param city 已解析的查询城市
     * @return 供高德地址解析使用的目标地址
     */
    private String buildShopAddress(ShopViewDTO shop, String city) {
        StringBuilder builder = new StringBuilder();
        if (StrUtil.isNotBlank(city)) {
            builder.append(city);
        }
        if (StrUtil.isNotBlank(shop.area()) && !builder.toString().contains(shop.area())) {
            builder.append(shop.area());
        }
        if (StrUtil.isNotBlank(shop.address())) {
            builder.append(shop.address());
        }
        return builder.toString();
    }
}
