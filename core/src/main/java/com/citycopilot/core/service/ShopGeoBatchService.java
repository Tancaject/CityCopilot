package com.citycopilot.core.service;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.citycopilot.core.agent.AgentTool;
import com.citycopilot.core.dto.GeoBatchJobDTO;
import com.citycopilot.core.dto.GeoPointDTO;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.ShopGeoUpdateDTO;
import com.citycopilot.core.dto.UserDTO;
import com.citycopilot.core.entity.Shop;
import com.citycopilot.core.entity.ShopGeoBatchJob;
import com.citycopilot.core.entity.ShopGeoBatchJobItem;
import com.citycopilot.core.config.GeoProperties;
import com.citycopilot.core.mapper.ShopGeoBatchJobItemMapper;
import com.citycopilot.core.mapper.ShopGeoBatchJobMapper;
import com.citycopilot.core.security.PermissionCodes;
import com.citycopilot.core.utils.SystemConstants;
import com.citycopilot.core.utils.UserHolder;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class ShopGeoBatchService {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Resource
    private IShopService shopService;

    @Resource
    private AmapGeoService amapGeoService;

    @Resource
    private ShopGeoBatchJobMapper jobMapper;

    @Resource
    private ShopGeoBatchJobItemMapper jobItemMapper;

    @Resource
    private OperationLogService operationLogService;

    @Resource
    private GeoProperties geoProperties;

    @AgentTool(
            name = "start_batch_geocode",
            description = "Start a batch geocode job for shops",
            requiredPermissions = {PermissionCodes.GEO_WRITE},
            idempotent = false
    )
    public GeoBatchJobDTO startGeocodeJob(String city, String keyword, Boolean verified, Boolean onlyMissing, Integer limit) {
        int size = normalizeLimit(limit);
        String scopeCity = resolveCity(city);
        List<Shop> shops = selectTargetShops(keyword, Boolean.TRUE.equals(onlyMissing), size);
        UserDTO operator = UserHolder.getUser();
        return createAndStartJob(scopeCity, keyword, Boolean.TRUE.equals(verified), Boolean.TRUE.equals(onlyMissing), size, shops, operator, "geo_batch_start");
    }

    @AgentTool(
            name = "list_geo_batch_jobs",
            description = "List shop geocode batch jobs",
            requiredPermissions = {PermissionCodes.GEO_READ}
    )
    public Page<ShopGeoBatchJob> queryJobs(Integer current, String status) {
        int pageNo = current == null || current < 1 ? 1 : current;
        return jobMapper.selectPage(
                new Page<>(pageNo, SystemConstants.MAX_PAGE_SIZE),
                new QueryWrapper<ShopGeoBatchJob>()
                        .eq(status != null && !status.isBlank(), "status", status)
                        .orderByDesc("id")
        );
    }

    public Result cancelJob(String jobId) {
        ShopGeoBatchJob job = findJob(jobId);
        if (job == null) {
            return Result.fail("GEO_BATCH_NOT_FOUND", "批量任务不存在");
        }
        if ("finished".equals(job.getStatus()) || "canceled".equals(job.getStatus())) {
            return Result.fail("GEO_BATCH_STATUS_INVALID", "当前任务状态不能取消");
        }
        GeoBatchJobDTO before = toDTO(job);
        LocalDateTime now = LocalDateTime.now();
        job.setStatus("canceled")
                .setMessage("任务已取消")
                .setFinishedAt(now)
                .setUpdateTime(now);
        jobMapper.updateById(job);
        getJobItems(jobId).stream()
                .filter(item -> "queued".equals(item.getStatus()) || "running".equals(item.getStatus()))
                .forEach(item -> {
                    item.setStatus("canceled").setMessage("任务已取消").setUpdateTime(now);
                    jobItemMapper.updateById(item);
                });
        operationLogService.record("geo", "geo_batch_cancel", "geo_batch_job", null, jobId, before, toDTO(job), true, null);
        return Result.ok(toDTO(job));
    }

    @AgentTool(
            name = "retry_geo_batch_failed_items",
            description = "Retry failed items from a geocode batch job",
            requiredPermissions = {PermissionCodes.GEO_WRITE},
            idempotent = false
    )
    public Result retryFailedItems(String sourceJobId, Boolean verified, Integer limit) {
        ShopGeoBatchJob source = findJob(sourceJobId);
        if (source == null) {
            return Result.fail("GEO_BATCH_NOT_FOUND", "批量任务不存在");
        }
        int size = normalizeLimit(limit);
        List<ShopGeoBatchJobItem> failedItems = jobItemMapper.selectList(new QueryWrapper<ShopGeoBatchJobItem>()
                .eq("job_id", sourceJobId)
                .eq("status", "failed")
                .orderByAsc("id")
                .last("LIMIT " + size));
        if (failedItems.isEmpty()) {
            return Result.fail("GEO_BATCH_RETRY_EMPTY", "没有可重试的失败明细");
        }
        List<Long> shopIds = failedItems.stream().map(ShopGeoBatchJobItem::getShopId).toList();
        List<Shop> shops = shopService.listByIds(shopIds);
        if (shops.isEmpty()) {
            return Result.fail("SHOP_NOT_FOUND", "失败明细对应的店铺不存在");
        }
        GeoBatchJobDTO retryJob = createAndStartJob(
                source.getCity(),
                "retry:" + sourceJobId,
                verified == null ? Boolean.TRUE.equals(source.getVerified()) : Boolean.TRUE.equals(verified),
                false,
                size,
                shops,
                UserHolder.getUser(),
                "geo_batch_retry"
        );
        return Result.ok(retryJob);
    }

    private GeoBatchJobDTO createAndStartJob(
            String city,
            String keyword,
            boolean verified,
            boolean onlyMissing,
            int size,
            List<Shop> shops,
            UserDTO operator,
            String auditAction
    ) {
        String jobId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        ShopGeoBatchJob job = new ShopGeoBatchJob()
                .setJobId(jobId)
                .setStatus(shops.isEmpty() ? "finished" : "queued")
                .setCity(city)
                .setKeyword(keyword)
                .setVerified(verified)
                .setOnlyMissing(onlyMissing)
                .setLimitSize(size)
                .setTotal(shops.size())
                .setProcessed(0)
                .setSuccess(0)
                .setFailed(0)
                .setMessage(shops.isEmpty() ? "没有需要处理的店铺" : "等待执行")
                .setOperatorId(operator == null ? null : operator.getId())
                .setOperatorName(operator == null ? null : operator.getNickName())
                .setStartedAt(now)
                .setCreateTime(now)
                .setUpdateTime(now);
        if (shops.isEmpty()) {
            job.setFinishedAt(now);
        }
        jobMapper.insert(job);
        shops.forEach(shop -> jobItemMapper.insert(new ShopGeoBatchJobItem()
                .setJobId(jobId)
                .setShopId(shop.getId())
                .setShopName(shop.getName())
                .setStatus("queued")
                .setMessage("等待执行")
                .setBeforeData(toJson(shop))
                .setCreateTime(now)
                .setUpdateTime(now)));
        GeoBatchJobDTO jobDTO = toDTO(job);
        operationLogService.record("geo", auditAction, "geo_batch_job", null, jobId, null, jobDTO, true, null);
        if (!shops.isEmpty()) {
            executor.submit(() -> runGeocodeJob(jobId, city, verified, shops, operator));
        }
        return jobDTO;
    }

    public GeoBatchJobDTO getJob(String jobId) {
        ShopGeoBatchJob job = findJob(jobId);
        if (job == null) {
            return null;
        }
        return toDTO(job);
    }

    public List<ShopGeoBatchJobItem> getJobItems(String jobId) {
        return jobItemMapper.selectList(new QueryWrapper<ShopGeoBatchJobItem>()
                .eq("job_id", jobId)
                .orderByAsc("id"));
    }

    private List<Shop> selectTargetShops(String keyword, boolean onlyMissing, int limit) {
        return shopService.query()
                .like(keyword != null && !keyword.isBlank(), "name", keyword)
                .and(onlyMissing, wrapper -> wrapper.isNull("x").or().isNull("y").or().isNull("geo_status").or().ne("geo_status", "verified"))
                .last("LIMIT " + limit)
                .list();
    }

    private void runGeocodeJob(String jobId, String city, boolean verified, List<Shop> shops, UserDTO operator) {
        if (operator != null) {
            UserHolder.saveUser(operator);
        }
        try {
            ShopGeoBatchJob job = findJob(jobId);
            if (job == null) {
                return;
            }
            if ("canceled".equals(job.getStatus())) {
                return;
            }
            job.setStatus("running").setMessage("正在批量解析地址").setUpdateTime(LocalDateTime.now());
            jobMapper.updateById(job);

            int processed = 0;
            int success = 0;
            int failed = 0;
            for (Shop shop : shops) {
                if (isCanceled(jobId)) {
                    updateProgress(jobId, processed, success, failed, "任务已取消");
                    break;
                }
                processed++;
                markItemRunning(jobId, shop);
                try {
                    Result geocode = amapGeoService.geocode(buildAddress(shop, city), city);
                    if (!Boolean.TRUE.equals(geocode.getSuccess())) {
                        failed++;
                        updateItem(jobId, shop, "failed", geocode.getErrorMsg(), null);
                        updateProgress(jobId, processed, success, failed, shop.getName() + "：" + geocode.getErrorMsg());
                        continue;
                    }
                    GeoPointDTO point = (GeoPointDTO) geocode.getData();
                    ShopGeoUpdateDTO update = new ShopGeoUpdateDTO();
                    update.setId(shop.getId());
                    update.setX(point.getX());
                    update.setY(point.getY());
                    update.setAdcode(point.getAdcode());
                    update.setGeoSource("amap_batch_geocode");
                    update.setGeoStatus(verified ? "verified" : "pending");
                    Result updateResult = shopService.updateShopGeo(update);
                    if (Boolean.TRUE.equals(updateResult.getSuccess())) {
                        success++;
                        updateItem(jobId, shop, "success", "已写回", updateResult.getData());
                        updateProgress(jobId, processed, success, failed, shop.getName() + " 已写回");
                    } else {
                        failed++;
                        updateItem(jobId, shop, "failed", updateResult.getErrorMsg(), null);
                        updateProgress(jobId, processed, success, failed, shop.getName() + "：" + updateResult.getErrorMsg());
                    }
                } catch (Exception error) {
                    failed++;
                    log.warn("Batch geocode shop {} failed", shop.getId(), error);
                    updateItem(jobId, shop, "failed", "处理失败", null);
                    updateProgress(jobId, processed, success, failed, shop.getName() + "：处理失败");
                }
            }

            ShopGeoBatchJob finished = findJob(jobId);
            if (finished != null && !"canceled".equals(finished.getStatus())) {
                finished.setStatus("finished")
                        .setProcessed(processed)
                        .setSuccess(success)
                        .setFailed(failed)
                        .setMessage("批量校准完成")
                        .setFinishedAt(LocalDateTime.now())
                        .setUpdateTime(LocalDateTime.now());
                jobMapper.updateById(finished);
                operationLogService.record("geo", "geo_batch_finish", "geo_batch_job", null, jobId, null, toDTO(finished), true, null);
            }
        } finally {
            UserHolder.removeUser();
        }
    }

    private void updateProgress(String jobId, int processed, int success, int failed, String message) {
        ShopGeoBatchJob job = findJob(jobId);
        if (job == null) {
            return;
        }
        job.setProcessed(processed)
                .setSuccess(success)
                .setFailed(failed)
                .setMessage(message)
                .setUpdateTime(LocalDateTime.now());
        jobMapper.updateById(job);
    }

    private String buildAddress(Shop shop, String city) {
        StringBuilder builder = new StringBuilder();
        if (city != null && !city.isBlank()) {
            builder.append(city);
        }
        if (shop.getArea() != null && !shop.getArea().isBlank() && !builder.toString().contains(shop.getArea())) {
            builder.append(shop.getArea());
        }
        if (shop.getAddress() != null) {
            builder.append(shop.getAddress());
        }
        return builder.toString();
    }

    private int normalizeLimit(Integer limit) {
        int maxBatchSize = geoProperties == null || geoProperties.getMaxBatchSize() == null || geoProperties.getMaxBatchSize() <= 0
                ? 100
                : geoProperties.getMaxBatchSize();
        if (limit == null) {
            return Math.min(20, maxBatchSize);
        }
        return Math.max(1, Math.min(limit, maxBatchSize));
    }

    private String resolveCity(String city) {
        return geoProperties == null ? city : geoProperties.resolveCity(city);
    }

    private ShopGeoBatchJob findJob(String jobId) {
        return jobMapper.selectOne(new QueryWrapper<ShopGeoBatchJob>().eq("job_id", jobId));
    }

    private ShopGeoBatchJobItem findItem(String jobId, Long shopId) {
        return jobItemMapper.selectOne(new QueryWrapper<ShopGeoBatchJobItem>()
                .eq("job_id", jobId)
                .eq("shop_id", shopId)
                .last("LIMIT 1"));
    }

    private boolean isCanceled(String jobId) {
        ShopGeoBatchJob job = findJob(jobId);
        return job != null && "canceled".equals(job.getStatus());
    }

    private void markItemRunning(String jobId, Shop shop) {
        updateItem(jobId, shop, "running", "正在处理", null);
    }

    private void updateItem(String jobId, Shop shop, String status, String message, Object afterData) {
        ShopGeoBatchJobItem item = findItem(jobId, shop.getId());
        if (item == null) {
            return;
        }
        item.setStatus(status)
                .setMessage(message)
                .setAfterData(afterData == null ? item.getAfterData() : toJson(afterData))
                .setUpdateTime(LocalDateTime.now());
        jobItemMapper.updateById(item);
    }

    private GeoBatchJobDTO toDTO(ShopGeoBatchJob job) {
        return new GeoBatchJobDTO()
                .setId(job.getId())
                .setJobId(job.getJobId())
                .setStatus(job.getStatus())
                .setCity(job.getCity())
                .setKeyword(job.getKeyword())
                .setVerified(job.getVerified())
                .setOnlyMissing(job.getOnlyMissing())
                .setLimitSize(job.getLimitSize())
                .setTotal(job.getTotal())
                .setProcessed(job.getProcessed())
                .setSuccess(job.getSuccess())
                .setFailed(job.getFailed())
                .setMessage(job.getMessage())
                .setOperatorId(job.getOperatorId())
                .setOperatorName(job.getOperatorName())
                .setStartedAt(job.getStartedAt() == null ? null : job.getStartedAt().toString())
                .setFinishedAt(job.getFinishedAt() == null ? null : job.getFinishedAt().toString());
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return JSONUtil.toJsonStr(value);
        } catch (Exception error) {
            return String.valueOf(value);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
