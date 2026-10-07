package com.citycopilot.core.service;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import com.citycopilot.core.mapper.ShopMapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
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

/**
 * 店铺地址批量地理编码服务。
 *
 * <p>任务和明细先持久化，再由单线程后台执行器调用高德解析并通过 {@link IShopService} 写回坐标。</p>
 */
@Slf4j
@Service
public class ShopGeoBatchService {

    /** 串行执行批处理，控制高德调用压力并简化任务内顺序与取消语义。 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    /** 店铺事实服务，负责按权限写回坐标。 */
    @Resource
    private IShopService shopService;

    @Resource
    private ShopMapper shopMapper;

    /** 高德地址解析适配服务。 */
    @Resource
    private AmapGeoService amapGeoService;

    /** 批任务主表 Mapper。 */
    @Resource
    private ShopGeoBatchJobMapper jobMapper;

    /** 批任务店铺明细 Mapper。 */
    @Resource
    private ShopGeoBatchJobItemMapper jobItemMapper;

    /** 操作审计服务，记录任务启动、取消、重试和完成。 */
    @Resource
    private OperationLogService operationLogService;

    /** 默认城市和最大批量大小配置。 */
    @Resource
    private GeoProperties geoProperties;

    /**
     * 创建并异步启动一项店铺批量地址解析任务。
     *
     * @param city 可选城市；空值使用默认城市
     * @param keyword 可选店铺名称过滤词
     * @param verified 写回后是否直接标记坐标已核验
     * @param onlyMissing 是否仅处理坐标缺失或未核验店铺
     * @param limit 最大处理数量，受全局配置限制
     * @return 已持久化任务摘要
     */
    public GeoBatchJobDTO startGeocodeJob(String city, String keyword, Boolean verified, Boolean onlyMissing, Integer limit) {
        int size = normalizeLimit(limit);
        String scopeCity = resolveCity(city);
        List<Shop> shops = selectTargetShops(keyword, Boolean.TRUE.equals(onlyMissing), size);
        UserDTO operator = UserHolder.getUser();
        return createAndStartJob(scopeCity, keyword, Boolean.TRUE.equals(verified), Boolean.TRUE.equals(onlyMissing), size, shops, operator, "geo_batch_start");
    }

    /**
     * 分页查询批量地理编码任务。
     *
     * @param current 从 1 开始的页码
     * @param status 可选任务状态
     * @return 按主键倒序排列的任务页
     */
    public Page<ShopGeoBatchJob> queryJobs(Integer current, String status) {
        int pageNo = current == null || current < 1 ? 1 : current;
        return jobMapper.selectPage(
                new Page<>(pageNo, SystemConstants.MAX_PAGE_SIZE),
                new QueryWrapper<ShopGeoBatchJob>()
                        .eq(status != null && !status.isBlank(), "status", status)
                        .orderByDesc("id")
        );
    }

    /**
     * 取消未结束任务，并把尚未完成的明细标记为取消。
     *
     * @param jobId 对外任务标识
     * @return 取消后的任务摘要或稳定错误
     */
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

    /**
     * 从历史任务中选取失败明细创建新的重试任务。
     *
     * @param sourceJobId 原任务标识
     * @param verified 新任务写回后是否标记已核验；空值沿用原任务
     * @param limit 最大重试数量
     * @return 新任务摘要
     */
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
        List<Shop> shops = shopMapper.selectByIds(shopIds);
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

    /**
     * 建立任务主记录和店铺明细，写审计后提交后台执行。
     *
     * @param city 解析城市
     * @param keyword 任务来源或筛选词
     * @param verified 写回核验状态
     * @param onlyMissing 是否只处理缺失坐标
     * @param size 请求批量大小
     * @param shops 已选目标店铺
     * @param operator 发起任务的用户快照
     * @param auditAction 审计操作名
     * @return 新任务摘要
     */
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
            // 传入操作者快照，使后台线程继续遵守店铺所有权与审计身份规则。
            executor.submit(() -> runGeocodeJob(jobId, city, verified, shops, operator));
        }
        return jobDTO;
    }

    /**
     * 查询单个任务摘要。
     *
     * @param jobId 对外任务标识
     * @return 任务摘要；不存在时为空
     */
    public GeoBatchJobDTO getJob(String jobId) {
        ShopGeoBatchJob job = findJob(jobId);
        if (job == null) {
            return null;
        }
        return toDTO(job);
    }

    /**
     * 查询任务全部明细。
     *
     * @param jobId 对外任务标识
     * @return 按创建顺序排列的店铺明细
     */
    public List<ShopGeoBatchJobItem> getJobItems(String jobId) {
        return jobItemMapper.selectList(new QueryWrapper<ShopGeoBatchJobItem>()
                .eq("job_id", jobId)
                .orderByAsc("id"));
    }

    /**
     * 按名称和坐标缺失条件选择有限数量的目标店铺。
     *
     * @param keyword 可选店铺名称关键词
     * @param onlyMissing 是否只选坐标缺失或未核验店铺
     * @param limit 最大数量
     * @return 待处理店铺
     */
    private List<Shop> selectTargetShops(String keyword, boolean onlyMissing, int limit) {
        return new QueryChainWrapper<>(shopMapper)
                .like(keyword != null && !keyword.isBlank(), "name", keyword)
                .and(onlyMissing, wrapper -> wrapper.isNull("x").or().isNull("y").or().isNull("geo_status").or().ne("geo_status", "verified"))
                .last("LIMIT " + limit)
                .list();
    }

    /**
     * 在后台线程中逐店调用高德解析、写回坐标并更新任务进度。
     *
     * @param jobId 任务标识
     * @param city 解析城市
     * @param verified 写回核验状态
     * @param shops 目标店铺快照
     * @param operator 发起人快照
     */
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
            // 线程池会复用线程，必须清理 ThreadLocal，避免下一任务继承前一位操作者。
            UserHolder.removeUser();
        }
    }

    /**
     * 持久化任务级计数和最后一条进度信息。
     *
     * @param jobId 任务标识
     * @param processed 已处理数量
     * @param success 成功数量
     * @param failed 失败数量
     * @param message 当前进度说明
     */
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

    /**
     * 拼接城市、行政区和门牌地址，减少同名道路的地理编码歧义。
     *
     * @param shop 目标店铺
     * @param city 解析城市
     * @return 提交高德的完整地址
     */
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

    /**
     * 将批量大小限制在配置上限内。
     *
     * @param limit 调用方请求值
     * @return 至少为 1 的有效批量大小
     */
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

    /**
     * 更新单个店铺明细的状态和可选写回快照。
     *
     * @param jobId 任务标识
     * @param shop 店铺
     * @param status 明细状态
     * @param message 处理说明
     * @param afterData 写回后数据
     */
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

    /**
     * 将任务实体转换成对外 DTO，并把时间统一转为字符串。
     *
     * @param job 任务实体
     * @return 任务摘要
     */
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

    /**
     * 尽力序列化审计快照；失败时退化为对象字符串。
     *
     * @param value 待记录值
     * @return JSON、字符串或 {@code null}
     */
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

    /** Spring 容器关闭时停止接收新的后台批任务。 */
    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
