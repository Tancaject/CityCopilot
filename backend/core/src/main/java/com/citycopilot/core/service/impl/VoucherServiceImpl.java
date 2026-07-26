package com.citycopilot.core.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.commerce.CommercePortClient;
import com.citycopilot.core.entity.SeckillVoucher;
import com.citycopilot.core.entity.Voucher;
import com.citycopilot.core.mapper.VoucherMapper;
import com.citycopilot.core.service.ISeckillVoucherService;
import com.citycopilot.core.service.IShopService;
import com.citycopilot.core.service.IVoucherService;
import com.citycopilot.core.service.OperationLogService;
import com.citycopilot.core.utils.SystemConstants;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.citycopilot.core.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Slf4j
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IShopService shopService;
    @Resource
    private OperationLogService operationLogService;
    @Resource
    private CommercePortClient commercePortClient;
    @Value("${citycopilot.commerce.enabled:false}")
    private boolean commerceEnabled;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        if (commerceEnabled) {
            return commercePortClient.vouchers(shopId, false, null, null);
        }
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        return Result.ok(vouchers);
    }

    @Override
    public Result queryAdminVouchers(Integer current, Long shopId, String keyword, Integer status) {
        if (commerceEnabled) {
            return commercePortClient.vouchers(shopId, true, keyword, status);
        }
        int pageNo = current == null || current < 1 ? 1 : current;
        Page<Voucher> page = query()
                .eq(shopId != null, "shop_id", shopId)
                .eq(status != null, "status", status)
                .like(StrUtil.isNotBlank(keyword), "title", keyword)
                .orderByDesc("id")
                .page(new Page<>(pageNo, SystemConstants.MAX_PAGE_SIZE));
        attachSeckillFields(page.getRecords());
        return Result.ok(page);
    }

    @Override
    @Transactional
    public Result createVoucher(Voucher voucher) {
        if (commerceEnabled) {
            return commercePortClient.createVoucher(voucher);
        }
        Result validation = validateNewVoucher(voucher);
        if (!Boolean.TRUE.equals(validation.getSuccess())) {
            return validation;
        }
        shopService.assertWritableByCurrentUser(voucher.getShopId());
        LocalDateTime now = LocalDateTime.now();
        voucher.setType(normalizeType(voucher.getType()));
        voucher.setStatus(normalizeStatus(voucher.getStatus(), 1));
        voucher.setCreateTime(now);
        voucher.setUpdateTime(now);
        save(voucher);

        if (isSeckill(voucher.getType())) {
            upsertSeckillVoucher(voucher, now);
            syncRedisStock(voucher.getId(), voucher.getStock());
        }
        Voucher saved = getById(voucher.getId());
        attachSeckillFields(List.of(saved));
        operationLogService.record("voucher", "voucher_create", "voucher", voucher.getId(), String.valueOf(voucher.getId()), null, saved, true, null);
        return Result.ok(voucher.getId());
    }

    @Override
    @Transactional
    public Result updateVoucher(Voucher voucher) {
        if (commerceEnabled) {
            if (voucher == null || voucher.getId() == null) {
                return Result.fail("BAD_REQUEST", "优惠券id不能为空");
            }
            return commercePortClient.updateVoucher(voucher.getId(), voucher);
        }
        if (voucher == null || voucher.getId() == null) {
            return Result.fail("BAD_REQUEST", "优惠券id不能为空");
        }
        Voucher existing = getById(voucher.getId());
        if (existing == null) {
            return Result.fail("VOUCHER_NOT_FOUND", "优惠券不存在");
        }
        shopService.assertWritableByCurrentUser(existing.getShopId());
        SeckillVoucher existingSeckill = isSeckill(existing.getType()) ? seckillVoucherService.getById(existing.getId()) : null;
        Result validation = validateExistingVoucher(voucher, existing, existingSeckill);
        if (!Boolean.TRUE.equals(validation.getSuccess())) {
            return validation;
        }

        Voucher before = getById(existing.getId());
        attachSeckillFields(List.of(before));

        LocalDateTime now = LocalDateTime.now();
        Voucher patch = new Voucher()
                .setId(existing.getId())
                .setShopId(voucher.getShopId() == null ? existing.getShopId() : voucher.getShopId())
                .setTitle(StrUtil.blankToDefault(voucher.getTitle(), existing.getTitle()).trim())
                .setSubTitle(voucher.getSubTitle())
                .setRules(voucher.getRules())
                .setPayValue(voucher.getPayValue() == null ? existing.getPayValue() : voucher.getPayValue())
                .setActualValue(voucher.getActualValue() == null ? existing.getActualValue() : voucher.getActualValue())
                .setType(existing.getType())
                .setStatus(normalizeStatus(voucher.getStatus(), existing.getStatus()))
                .setUpdateTime(now);
        updateById(patch);

        if (isSeckill(existing.getType())) {
            Voucher seckillPatch = new Voucher()
                    .setId(existing.getId())
                    .setStock(voucher.getStock() == null && existingSeckill != null ? existingSeckill.getStock() : voucher.getStock())
                    .setBeginTime(voucher.getBeginTime() == null && existingSeckill != null ? existingSeckill.getBeginTime() : voucher.getBeginTime())
                    .setEndTime(voucher.getEndTime() == null && existingSeckill != null ? existingSeckill.getEndTime() : voucher.getEndTime());
            upsertSeckillVoucher(seckillPatch, now);
            syncRedisStock(existing.getId(), seckillPatch.getStock());
        }

        Voucher after = getById(existing.getId());
        attachSeckillFields(List.of(after));
        operationLogService.record("voucher", "voucher_update", "voucher", existing.getId(), String.valueOf(existing.getId()), before, after, true, null);
        return Result.ok(after);
    }

    @Override
    @Transactional
    public Result updateVoucherStatus(Long id, Integer status) {
        if (commerceEnabled) {
            return commercePortClient.updateVoucher(id, Map.of("status", status));
        }
        if (id == null) {
            return Result.fail("BAD_REQUEST", "优惠券id不能为空");
        }
        if (!isValidStatus(status)) {
            return Result.fail("BAD_REQUEST", "优惠券状态不合法");
        }
        Voucher before = getById(id);
        if (before == null) {
            return Result.fail("VOUCHER_NOT_FOUND", "优惠券不存在");
        }
        shopService.assertWritableByCurrentUser(before.getShopId());
        attachSeckillFields(List.of(before));
        update()
                .eq("id", id)
                .set("status", status)
                .set("update_time", LocalDateTime.now())
                .update();
        Voucher after = getById(id);
        attachSeckillFields(List.of(after));
        operationLogService.record("voucher", "voucher_status_update", "voucher", id, String.valueOf(id), before, after, true, null);
        return Result.ok(after);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        voucher.setType(1);
        Result result = createVoucher(voucher);
        if (!Boolean.TRUE.equals(result.getSuccess())) {
            throw new IllegalArgumentException(result.getErrorMsg());
        }
    }

    private Result validateNewVoucher(Voucher voucher) {
        if (voucher == null) {
            return Result.fail("BAD_REQUEST", "优惠券不能为空");
        }
        Integer type = normalizeType(voucher.getType());
        if (!isValidType(type)) {
            return Result.fail("BAD_REQUEST", "优惠券类型不合法");
        }
        Result basic = validateBasicFields(voucher.getShopId(), voucher.getTitle(), voucher.getPayValue(), voucher.getActualValue(), normalizeStatus(voucher.getStatus(), 1));
        if (!Boolean.TRUE.equals(basic.getSuccess())) {
            return basic;
        }
        if (isSeckill(type)) {
            return validateSeckillFields(voucher.getStock(), voucher.getBeginTime(), voucher.getEndTime(), true);
        }
        return Result.ok();
    }

    private Result validateExistingVoucher(Voucher incoming, Voucher existing, SeckillVoucher existingSeckill) {
        Long shopId = incoming.getShopId() == null ? existing.getShopId() : incoming.getShopId();
        String title = StrUtil.blankToDefault(incoming.getTitle(), existing.getTitle());
        Long payValue = incoming.getPayValue() == null ? existing.getPayValue() : incoming.getPayValue();
        Long actualValue = incoming.getActualValue() == null ? existing.getActualValue() : incoming.getActualValue();
        Integer status = normalizeStatus(incoming.getStatus(), existing.getStatus());
        Result basic = validateBasicFields(shopId, title, payValue, actualValue, status);
        if (!Boolean.TRUE.equals(basic.getSuccess())) {
            return basic;
        }
        if (isSeckill(existing.getType())) {
            Integer stock = incoming.getStock() == null && existingSeckill != null ? existingSeckill.getStock() : incoming.getStock();
            LocalDateTime beginTime = incoming.getBeginTime() == null && existingSeckill != null ? existingSeckill.getBeginTime() : incoming.getBeginTime();
            LocalDateTime endTime = incoming.getEndTime() == null && existingSeckill != null ? existingSeckill.getEndTime() : incoming.getEndTime();
            return validateSeckillFields(stock, beginTime, endTime, false);
        }
        return Result.ok();
    }

    private Result validateBasicFields(Long shopId, String title, Long payValue, Long actualValue, Integer status) {
        if (shopId == null) {
            return Result.fail("BAD_REQUEST", "店铺id不能为空");
        }
        if (shopService.getById(shopId) == null) {
            return Result.fail("SHOP_NOT_FOUND", "店铺不存在");
        }
        if (StrUtil.isBlank(title)) {
            return Result.fail("BAD_REQUEST", "优惠券标题不能为空");
        }
        if (payValue == null || payValue < 0) {
            return Result.fail("BAD_REQUEST", "支付金额不合法");
        }
        if (actualValue == null || actualValue <= 0) {
            return Result.fail("BAD_REQUEST", "抵扣金额不合法");
        }
        if (payValue > actualValue) {
            return Result.fail("BAD_REQUEST", "支付金额不能大于抵扣金额");
        }
        if (!isValidStatus(status)) {
            return Result.fail("BAD_REQUEST", "优惠券状态不合法");
        }
        return Result.ok();
    }

    private Result validateSeckillFields(Integer stock, LocalDateTime beginTime, LocalDateTime endTime, boolean creating) {
        if (stock == null || (creating ? stock <= 0 : stock < 0)) {
            return Result.fail("BAD_REQUEST", "秒杀库存不合法");
        }
        if (beginTime == null || endTime == null) {
            return Result.fail("BAD_REQUEST", "秒杀开始和结束时间不能为空");
        }
        if (!beginTime.isBefore(endTime)) {
            return Result.fail("BAD_REQUEST", "秒杀结束时间必须晚于开始时间");
        }
        return Result.ok();
    }

    private void upsertSeckillVoucher(Voucher voucher, LocalDateTime now) {
        SeckillVoucher seckillVoucher = new SeckillVoucher()
                .setVoucherId(voucher.getId())
                .setStock(voucher.getStock())
                .setBeginTime(voucher.getBeginTime())
                .setEndTime(voucher.getEndTime())
                .setUpdateTime(now);
        if (seckillVoucherService.getById(voucher.getId()) == null) {
            seckillVoucher.setCreateTime(now);
            seckillVoucherService.save(seckillVoucher);
            return;
        }
        seckillVoucherService.updateById(seckillVoucher);
    }

    private void attachSeckillFields(List<Voucher> vouchers) {
        if (vouchers == null || vouchers.isEmpty()) {
            return;
        }
        List<Long> seckillIds = vouchers.stream()
                .filter(voucher -> voucher != null && isSeckill(voucher.getType()) && voucher.getId() != null)
                .map(Voucher::getId)
                .toList();
        if (seckillIds.isEmpty()) {
            return;
        }
        Map<Long, SeckillVoucher> seckillMap = seckillVoucherService.listByIds(seckillIds).stream()
                .collect(Collectors.toMap(SeckillVoucher::getVoucherId, Function.identity(), (left, right) -> left));
        vouchers.forEach(voucher -> {
            if (voucher == null) {
                return;
            }
            SeckillVoucher seckillVoucher = seckillMap.get(voucher.getId());
            if (seckillVoucher == null) {
                return;
            }
            voucher.setStock(seckillVoucher.getStock());
            voucher.setBeginTime(seckillVoucher.getBeginTime());
            voucher.setEndTime(seckillVoucher.getEndTime());
        });
    }

    private void syncRedisStock(Long voucherId, Integer stock) {
        if (voucherId == null || stock == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucherId, stock.toString());
        } catch (Exception error) {
            log.warn("Sync seckill stock to Redis failed, voucherId={}: {}", voucherId, error.getMessage());
        }
    }

    private Integer normalizeType(Integer type) {
        return type == null ? 0 : type;
    }

    private Integer normalizeStatus(Integer status, Integer fallback) {
        return status == null ? fallback : status;
    }

    private boolean isValidType(Integer type) {
        return Integer.valueOf(0).equals(type) || Integer.valueOf(1).equals(type);
    }

    private boolean isValidStatus(Integer status) {
        return Integer.valueOf(1).equals(status) || Integer.valueOf(2).equals(status) || Integer.valueOf(3).equals(status);
    }

    private boolean isSeckill(Integer type) {
        return Integer.valueOf(1).equals(type);
    }
}
