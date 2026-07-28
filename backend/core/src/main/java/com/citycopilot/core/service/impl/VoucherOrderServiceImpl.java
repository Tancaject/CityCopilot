package com.citycopilot.core.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.citycopilot.core.domain.order.OrderStatus;
import com.citycopilot.core.commerce.CommercePortClient;
import com.citycopilot.core.domain.order.OrderProcessingResult;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.VoucherOrderDTO;
import com.citycopilot.core.entity.SeckillReservation;
import com.citycopilot.core.entity.SeckillVoucher;
import com.citycopilot.core.entity.Shop;
import com.citycopilot.core.entity.Voucher;
import com.citycopilot.core.entity.VoucherOrder;
import com.citycopilot.core.mapper.VoucherOrderMapper;
import com.citycopilot.core.service.IShopService;
import com.citycopilot.core.service.ISeckillVoucherService;
import com.citycopilot.core.service.IVoucherService;
import com.citycopilot.core.service.IVoucherOrderService;
import com.citycopilot.core.service.OperationLogService;
import com.citycopilot.core.service.SeckillReservationService;
import com.citycopilot.core.event.DomainEventService;
import com.citycopilot.core.utils.RedisIdWorker;
import com.citycopilot.core.utils.SystemConstants;
import com.citycopilot.core.utils.UserHolder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Lazy;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.domain.Range;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.UUID;

import static com.citycopilot.core.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.citycopilot.core.utils.RedisConstants.SECKILL_STOCK_KEY;
import static com.citycopilot.core.utils.RedisConstants.SECKILL_IDEMPOTENCY_KEY;
import static com.citycopilot.core.utils.RedisConstants.SECKILL_RETRY_KEY;
import static com.citycopilot.core.utils.RedisConstants.SECKILL_ORDER_STREAM;
import static com.citycopilot.core.utils.RedisConstants.SECKILL_DEAD_LETTER_STREAM;
import static com.citycopilot.core.utils.RedisConstants.SECKILL_RESERVATION_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private IVoucherService voucherService;
    @Resource
    private IShopService shopService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Autowired(required = false)
    private RedissonClient redissonClient;
    @Resource
    private OperationLogService operationLogService;
    @Resource
    private SeckillReservationService seckillReservationService;
    @Resource
    private DomainEventService domainEventService;
    @Resource
    private CommercePortClient commercePortClient;
    @Resource
    @Lazy
    private IVoucherOrderService voucherOrderService;  // 自注入，解决异步线程代理问题

    @Value("${citycopilot.seckill.stream-worker.enabled:true}")
    private boolean streamWorkerEnabled;
    @Value("${citycopilot.commerce.enabled:false}")
    private boolean commerceEnabled;
    @Value("${citycopilot.seckill.stream-worker.max-retries:3}")
    private int streamMaxRetries;
    @Autowired(required = false)
    private MeterRegistry meterRegistry;
    private volatile boolean seckillWorkerRunning;
    private final String streamConsumerName = streamConsumerName();
    private static final Duration PENDING_CLAIM_IDLE = Duration.ofSeconds(30);

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final DefaultRedisScript<Long> CONFIRM_RESERVATION_SCRIPT;
    private static final DefaultRedisScript<Long> RELEASE_RESERVATION_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("lua/seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
        CONFIRM_RESERVATION_SCRIPT = new DefaultRedisScript<>();
        CONFIRM_RESERVATION_SCRIPT.setLocation(new ClassPathResource("lua/confirm_reservation.lua"));
        CONFIRM_RESERVATION_SCRIPT.setResultType(Long.class);
        RELEASE_RESERVATION_SCRIPT = new DefaultRedisScript<>();
        RELEASE_RESERVATION_SCRIPT.setLocation(new ClassPathResource("lua/release_reservation.lua"));
        RELEASE_RESERVATION_SCRIPT.setResultType(Long.class);
    }

    private final ExecutorService seckillOrderExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "seckill-order-worker");
        thread.setDaemon(true);
        return thread;
    });

    @PostConstruct
    private void init(){
        if (commerceEnabled) {
            log.warn("Legacy Core seckill worker fenced because Commerce Service owns order writes");
            return;
        }
        if (!isSeckillWorkerReady()) {
            if (streamWorkerEnabled) {
                log.warn("Seckill stream worker is disabled because Redisson is not available");
            }
            return;
        }
        seckillWorkerRunning = true;
        seckillOrderExecutor.submit(new SeckillOrderWorker());
    }

    @PreDestroy
    private void shutdown() {
        seckillWorkerRunning = false;
        seckillOrderExecutor.shutdownNow();
    }
    private class SeckillOrderWorker implements Runnable {

        @Override
        public void run() {
            while (seckillWorkerRunning && !Thread.currentThread().isInterrupted()) {
                MapRecord<String, Object, Object> record = null;
                try {
                    record = readRecord(ReadOffset.lastConsumed(), Duration.ofSeconds(2));
                    if (record == null) {
                        record = claimStaleRecord();
                    }
                    if (record == null) {
                        continue;
                    }
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(record.getValue(), new VoucherOrder(), true);
                    OrderProcessingResult result = handleVoucherOrder(voucherOrder);
                    recordOutcome(result);
                    if (result.acknowledgeable()) {
                        acknowledge(record);
                    } else {
                        handleRecordFailure(record, new IllegalStateException(result.code() + ": " + result.message()));
                    }
                } catch (Exception error) {
                    if (isWorkerShutdown(error)) {
                        break;
                    }
                    if (record == null) {
                        log.error("读取秒杀订单流失败", error);
                    } else {
                        handleRecordFailure(record, error);
                    }
                }
            }
        }

        private MapRecord<String, Object, Object> readRecord(ReadOffset offset, Duration block) {
            StreamReadOptions options = StreamReadOptions.empty().count(1);
            if (!block.isZero()) {
                options = options.block(block);
            }
            List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", streamConsumerName),
                            options,
                            StreamOffset.create(SECKILL_ORDER_STREAM, offset)
            );
            return list == null || list.isEmpty() ? null : list.get(0);
        }

        private MapRecord<String, Object, Object> claimStaleRecord() {
            PendingMessages pending = stringRedisTemplate.opsForStream().pending(
                    SECKILL_ORDER_STREAM,
                    "g1",
                    Range.unbounded(),
                    10
            );
            if (pending == null) {
                return null;
            }
            for (PendingMessage message : pending) {
                if (message.getElapsedTimeSinceLastDelivery().compareTo(PENDING_CLAIM_IDLE) < 0) {
                    continue;
                }
                List<MapRecord<String, Object, Object>> claimed = stringRedisTemplate.opsForStream().claim(
                        SECKILL_ORDER_STREAM,
                        "g1",
                        streamConsumerName,
                        PENDING_CLAIM_IDLE,
                        message.getId()
                );
                if (claimed != null && !claimed.isEmpty()) {
                    return claimed.get(0);
                }
            }
            return null;
        }

        private void acknowledge(MapRecord<String, Object, Object> record) {
            stringRedisTemplate.opsForStream().acknowledge(SECKILL_ORDER_STREAM, "g1", record.getId());
            stringRedisTemplate.delete(SECKILL_RETRY_KEY + record.getId().getValue());
        }

        private void handleRecordFailure(MapRecord<String, Object, Object> record, Exception error) {
            String retryKey = SECKILL_RETRY_KEY + record.getId().getValue();
            Long attempts = stringRedisTemplate.opsForValue().increment(retryKey);
            stringRedisTemplate.expire(retryKey, Duration.ofDays(1));
            if (meterRegistry != null) {
                Counter.builder("citycopilot.seckill.retry.count")
                        .description("Seckill stream processing retries")
                        .register(meterRegistry)
                        .increment();
            }
            if (!shouldDeadLetter(attempts == null ? 1 : attempts)) {
                log.warn("秒杀订单处理失败，将重试 recordId=" + record.getId()
                        + ", attempt=" + attempts + ", error=" + error.getMessage());
                return;
            }
            Map<String, String> deadLetter = new LinkedHashMap<>();
            record.getValue().forEach((key, value) -> deadLetter.put(String.valueOf(key), String.valueOf(value)));
            deadLetter.put("originalRecordId", record.getId().getValue());
            deadLetter.put("retryCount", String.valueOf(attempts));
            deadLetter.put("error", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            stringRedisTemplate.opsForStream().add(SECKILL_DEAD_LETTER_STREAM, deadLetter);
            acknowledge(record);
            if (meterRegistry != null) {
                Counter.builder("citycopilot.seckill.dead_letter.count")
                        .description("Seckill messages moved to the dead-letter stream")
                        .register(meterRegistry)
                        .increment();
            }
            log.error("秒杀订单已进入死信流 recordId=" + record.getId() + ", attempts=" + attempts, error);
        }
    }

    boolean shouldDeadLetter(long attempts) {
        return attempts >= Math.max(1, streamMaxRetries);
    }

    private boolean isWorkerShutdown(Exception error) {
        if (!seckillWorkerRunning || Thread.currentThread().isInterrupted()) {
            return true;
        }
        Throwable current = error;
        while (current != null) {
            if (current instanceof IllegalStateException
                    && current.getMessage() != null
                    && current.getMessage().contains("destroyed")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private OrderProcessingResult handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId=voucherOrder.getUserId();
        if (redissonClient == null) {
            throw new IllegalStateException("Redisson is required for async seckill order handling");
        }
        RLock rLock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = rLock.tryLock();
        if (!isLock) {
            return OrderProcessingResult.retryable("ORDER_LOCK_BUSY", "Order lock is busy");
        }
        // Keep the per-user lock until the proxied transaction returns.
        try {
            OrderProcessingResult result = voucherOrderService.createVoucherOrder(voucherOrder);
            if (result.outcome() == OrderProcessingResult.Outcome.ORDER_CREATED
                    || result.outcome() == OrderProcessingResult.Outcome.ALREADY_CREATED) {
                return confirmRedisReservation(voucherOrder)
                        ? result
                        : OrderProcessingResult.retryable(
                                "RESERVATION_CONFIRM_FAILED",
                                "MySQL order exists but Redis reservation confirmation is incomplete"
                        );
            }
            if (result.outcome() == OrderProcessingResult.Outcome.TERMINAL_REJECT) {
                return releaseRedisReservation(voucherOrder, result.code())
                        ? result
                        : OrderProcessingResult.retryable(
                                "RESERVATION_RELEASE_FAILED",
                                "Terminal order rejection is waiting for Redis reservation compensation"
                        );
            }
            return result;
        }  finally {
            rLock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId){
        return seckillVoucher(voucherId, null);
    }

    @Override
    public Result seckillVoucher(Long voucherId, String idempotencyKey){
        if (commerceEnabled) {
            return commercePortClient.seckill(voucherId, idempotencyKey);
        }
        if (voucherId == null) {
            return Result.fail("BAD_REQUEST", "优惠券id不能为空");
        }
        Voucher voucher = voucherService.getById(voucherId);
        if (voucher == null) {
            return Result.fail("VOUCHER_NOT_FOUND", "优惠券不存在");
        }
        if (!Integer.valueOf(1).equals(voucher.getStatus())) {
            return Result.fail("VOUCHER_NOT_AVAILABLE", "优惠券未上架");
        }
        if (!Integer.valueOf(1).equals(voucher.getType())) {
            return Result.fail("VOUCHER_NOT_SECKILL", "不是秒杀券");
        }
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (seckillVoucher == null) {
            return Result.fail("VOUCHER_NOT_AVAILABLE", "秒杀券配置不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        if (seckillVoucher.getBeginTime() != null && now.isBefore(seckillVoucher.getBeginTime())) {
            return Result.fail("SECKILL_NOT_STARTED", "秒杀尚未开始");
        }
        if (seckillVoucher.getEndTime() != null && now.isAfter(seckillVoucher.getEndTime())) {
            return Result.fail("SECKILL_ENDED", "秒杀已结束");
        }
        if (seckillVoucher.getStock() == null || seckillVoucher.getStock() <= 0) {
            return Result.fail("SECKILL_STOCK_NOT_ENOUGH", "库存不足！");
        }
        if (!isSeckillWorkerReady()) {
            return Result.fail("SECKILL_WORKER_UNAVAILABLE", "秒杀下单服务暂不可用，请稍后重试");
        }
        ensureRedisStock(voucherId, seckillVoucher.getStock());
        Long userId=UserHolder.getUser().getId();
        String idempotencyRedisKey = idempotencyRedisKey(userId, voucherId, idempotencyKey);
        if (idempotencyRedisKey != null) {
            String existingOrderId = stringRedisTemplate.opsForValue().get(idempotencyRedisKey);
            if (existingOrderId != null) {
                return Result.ok(existingOrderId);
            }
        }
        long orderId = redisIdWorker.nextId("order");
        if (idempotencyRedisKey != null) {
            Boolean claimed = stringRedisTemplate.opsForValue().setIfAbsent(
                    idempotencyRedisKey,
                    String.valueOf(orderId),
                    Duration.ofMinutes(10)
            );
            if (!Boolean.TRUE.equals(claimed)) {
                String existingOrderId = stringRedisTemplate.opsForValue().get(idempotencyRedisKey);
                if (existingOrderId != null) {
                    return Result.ok(existingOrderId);
                }
                return Result.fail("SECKILL_IDEMPOTENCY_CONFLICT", "请求正在处理中，请稍后查询订单");
            }
        }
        Long result=stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),//该脚本不需要传key，因此传一个空集合(不能传null)
                voucherId.toString(), userId.toString(), String.valueOf(orderId)  //转成String
        );
        int res=result.intValue();
        if (res!=0) {
            if (idempotencyRedisKey != null) {
                stringRedisTemplate.delete(idempotencyRedisKey);
            }
            //不为0.没有购买资格
            return Result.fail(res==1 ? "SECKILL_STOCK_NOT_ENOUGH" : "SECKILL_DUPLICATE_ORDER", res==1?"库存不足！":"不能重复下单！");
        }
        return Result.ok(String.valueOf(orderId));
    }

    private String idempotencyRedisKey(Long userId, Long voucherId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        String normalized = idempotencyKey.trim();
        if (normalized.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key 长度不能超过 128");
        }
        return SECKILL_IDEMPOTENCY_KEY + userId + ":" + voucherId + ":" + normalized;
    }

    @Override
    public Result queryMyOrders(Integer current) {
        if (commerceEnabled) {
            return commercePortClient.myOrders(current);
        }
        Long userId = UserHolder.getUser().getId();
        int pageNo = current == null || current < 1 ? 1 : current;
        Page<VoucherOrder> page = query()
                .eq("user_id", userId)
                .orderByDesc("create_time")
                .page(new Page<>(pageNo, SystemConstants.MAX_PAGE_SIZE));
        List<VoucherOrderDTO> records = buildOrderViews(page.getRecords());
        Page<VoucherOrderDTO> viewPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        viewPage.setRecords(records);
        return Result.ok(viewPage);
    }

    @Override
    public Result queryOrder(Long orderId) {
        if (commerceEnabled) {
            return commercePortClient.order(orderId, false);
        }
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = query()
                .eq("id", orderId)
                .eq("user_id", userId)
                .one();
        if (order == null) {
            return Result.ok(reservationView(orderId, seckillReservationService.find(orderId)));
        }
        return Result.ok(buildOrderViews(List.of(order)).get(0));
    }

    @Override
    public Result queryOrderForAdmin(Long orderId) {
        if (commerceEnabled) {
            return commercePortClient.order(orderId, true);
        }
        VoucherOrder order = getById(orderId);
        if (order == null) {
            return Result.fail("ORDER_NOT_FOUND", "订单不存在");
        }
        return Result.ok(orderView(order));
    }

    @Override
    @Transactional
    public Result cancelOrder(Long orderId) {
        if (commerceEnabled) {
            return commercePortClient.transition(orderId, "cancel", null, false);
        }
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = query()
                .eq("id", orderId)
                .eq("user_id", userId)
                .one();
        if (order == null) {
            return Result.fail("ORDER_NOT_FOUND", "订单不存在");
        }
        if (!order.orderStatus().canTransitionTo(OrderStatus.CANCELLED)) {
            return Result.fail("ORDER_STATUS_INVALID", "当前订单状态不可取消");
        }
        boolean updated = transition(order, OrderStatus.CANCELLED, userId, ignored -> {
        });
        if (!updated) {
            return Result.fail("ORDER_STATUS_CHANGED", "订单状态已变化，请刷新后重试");
        }
        restoreSeckillInventory(order.getVoucherId(), userId, true);
        recordOrderChange("order_cancel", orderId, order, true, null);
        return queryOrder(orderId);
    }

    @Override
    @Transactional
    public Result payOrder(Long orderId, Integer payType) {
        if (commerceEnabled) {
            return commercePortClient.transition(orderId, "pay", payType, false);
        }
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = query()
                .eq("id", orderId)
                .eq("user_id", userId)
                .one();
        if (order == null) {
            return Result.fail("ORDER_NOT_FOUND", "订单不存在");
        }
        if (!order.orderStatus().canTransitionTo(OrderStatus.PAID)) {
            return Result.fail("ORDER_STATUS_INVALID", "当前订单状态不可支付");
        }
        int payTypeValue = normalizePayType(payType);
        if (payTypeValue == 0) {
            return Result.fail("ORDER_PAY_TYPE_INVALID", "支付方式不支持");
        }
        LocalDateTime now = LocalDateTime.now();
        boolean updated = transition(order, OrderStatus.PAID, userId, wrapper -> wrapper
                .set(VoucherOrder::getPayType, payTypeValue)
                .set(VoucherOrder::getPayTime, now));
        if (!updated) {
            return Result.fail("ORDER_STATUS_CHANGED", "订单状态已变化，请刷新后重试");
        }
        recordOrderChange("order_pay", orderId, order, true, null);
        return queryOrder(orderId);
    }

    @Override
    @Transactional
    public Result useOrder(Long orderId) {
        if (commerceEnabled) {
            return commercePortClient.transition(orderId, "use", null, true);
        }
        VoucherOrder order = getById(orderId);
        if (order == null) {
            return Result.fail("ORDER_NOT_FOUND", "订单不存在");
        }
        if (!order.orderStatus().canTransitionTo(OrderStatus.USED)) {
            return Result.fail("ORDER_STATUS_INVALID", "当前订单状态不可核销");
        }
        LocalDateTime now = LocalDateTime.now();
        boolean updated = transition(order, OrderStatus.USED, null, wrapper ->
                wrapper.set(VoucherOrder::getUseTime, now));
        if (!updated) {
            return Result.fail("ORDER_STATUS_CHANGED", "订单状态已变化，请刷新后重试");
        }
        VoucherOrder refreshed = getById(orderId);
        operationLogService.record("order", "order_use", "voucher_order", orderId, String.valueOf(orderId), order, refreshed, true, null);
        return Result.ok(orderView(refreshed));
    }

    @Override
    @Transactional
    public Result refundOrder(Long orderId) {
        if (commerceEnabled) {
            return commercePortClient.transition(orderId, "refund", null, false);
        }
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = query()
                .eq("id", orderId)
                .eq("user_id", userId)
                .one();
        if (order == null) {
            return Result.fail("ORDER_NOT_FOUND", "订单不存在");
        }
        if (!order.orderStatus().canTransitionTo(OrderStatus.REFUNDING)) {
            return Result.fail("ORDER_STATUS_INVALID", "当前订单状态不可退款");
        }
        LocalDateTime now = LocalDateTime.now();
        boolean updated = transition(order, OrderStatus.REFUNDING, userId, ignored -> {
        });
        if (!updated) {
            return Result.fail("ORDER_STATUS_CHANGED", "订单状态已变化，请刷新后重试");
        }
        VoucherOrder refunding = getById(orderId);
        if (!transition(refunding, OrderStatus.REFUNDED, userId, wrapper ->
                wrapper.set(VoucherOrder::getRefundTime, now))) {
            throw new IllegalStateException("Refund completion transition lost a concurrent update");
        }
        restoreSeckillInventory(order.getVoucherId(), userId, false);
        recordOrderChange("order_refund", orderId, order, true, null);
        return queryOrder(orderId);
    }

    private List<VoucherOrderDTO> buildOrderViews(List<VoucherOrder> orders) {
        if (orders == null || orders.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> voucherIds = orders.stream()
                .map(VoucherOrder::getVoucherId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, Voucher> voucherMap = voucherIds.isEmpty()
                ? Collections.emptyMap()
                : voucherService.listByIds(voucherIds).stream()
                .collect(Collectors.toMap(Voucher::getId, Function.identity(), (left, right) -> left));
        List<Long> shopIds = voucherMap.values().stream()
                .map(Voucher::getShopId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, Shop> shopMap = shopIds.isEmpty()
                ? Collections.emptyMap()
                : shopService.listByIds(shopIds).stream()
                .collect(Collectors.toMap(Shop::getId, Function.identity(), (left, right) -> left));
        return orders.stream()
                .map(order -> toOrderView(order, voucherMap, shopMap))
                .toList();
    }

    private VoucherOrderDTO orderView(VoucherOrder order) {
        if (order == null) {
            return null;
        }
        return buildOrderViews(List.of(order)).get(0);
    }

    private VoucherOrderDTO toOrderView(VoucherOrder order, Map<Long, Voucher> voucherMap, Map<Long, Shop> shopMap) {
        Voucher voucher = voucherMap.get(order.getVoucherId());
        Shop shop = voucher == null ? null : shopMap.get(voucher.getShopId());
        VoucherOrderDTO view = new VoucherOrderDTO();
        view.setId(order.getId());
        view.setVoucherId(order.getVoucherId());
        view.setPayType(order.getPayType());
        view.setStatus(order.getStatus());
        view.setState(order.orderStatus().state());
        view.setStatusText(order.orderStatus().text());
        view.setCreateTime(order.getCreateTime());
        view.setPayTime(order.getPayTime());
        view.setUseTime(order.getUseTime());
        view.setRefundTime(order.getRefundTime());
        view.setUpdateTime(order.getUpdateTime());
        if (voucher != null) {
            view.setShopId(voucher.getShopId());
            view.setVoucherTitle(voucher.getTitle());
            view.setVoucherSubTitle(voucher.getSubTitle());
            view.setVoucherRules(voucher.getRules());
            view.setPayValue(voucher.getPayValue());
            view.setActualValue(voucher.getActualValue());
        }
        if (shop != null) {
            view.setShopName(shop.getName());
        }
        return view;
    }

    private VoucherOrderDTO reservationView(Long orderId, SeckillReservation reservation) {
        VoucherOrderDTO view = new VoucherOrderDTO();
        view.setId(orderId);
        if (reservation == null || SeckillReservationService.RESERVED.equals(reservation.getStatus())) {
            view.setState("processing");
            view.setStatusText("订单处理中");
            return view;
        }
        if (SeckillReservationService.ORDER_CREATED.equals(reservation.getStatus())) {
            view.setState("processing");
            view.setStatusText("订单已创建，查询视图同步中");
            return view;
        }
        view.setState("failed");
        view.setStatusText("订单创建失败，库存资格已释放");
        view.setFailureCode(reservation.getFailureCode());
        return view;
    }

    @Override
    @Transactional
    public OrderProcessingResult createVoucherOrder(VoucherOrder voucherOrder){
        Long userId= voucherOrder.getUserId();  // 从订单对象获取，不能用UserHolder（异步线程拿不到）
        SeckillReservation reservation = seckillReservationService.ensureReserved(voucherOrder);
        if (SeckillReservationService.ORDER_CREATED.equals(reservation.getStatus())) {
            return OrderProcessingResult.alreadyCreated();
        }
        if (SeckillReservationService.RELEASE_REQUIRED.equals(reservation.getStatus())
                || SeckillReservationService.RELEASED.equals(reservation.getStatus())) {
            return OrderProcessingResult.terminal(
                    reservation.getFailureCode() == null ? "RESERVATION_RELEASED" : reservation.getFailureCode(),
                    "Reservation has reached a terminal state"
            );
        }
        VoucherOrder sameOrder = getById(voucherOrder.getId());
        if (sameOrder != null) {
            seckillReservationService.transition(
                    voucherOrder.getId(),
                    SeckillReservationService.RESERVED,
                    SeckillReservationService.ORDER_CREATED,
                    null
            );
            return OrderProcessingResult.alreadyCreated();
        }
        Long count = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherOrder.getVoucherId())
                .and(wrapper -> wrapper.isNull("status").or().notIn("status", 4, 6))
                .count();
        if (count > 0) {
            seckillReservationService.transition(
                    voucherOrder.getId(),
                    SeckillReservationService.RESERVED,
                    SeckillReservationService.RELEASE_REQUIRED,
                    "DUPLICATE_ACTIVE_ORDER"
            );
            return OrderProcessingResult.terminal(
                    "DUPLICATE_ACTIVE_ORDER",
                    "Another active order already exists for this user and voucher"
            );
        }
        boolean success = seckillVoucherService.update().setSql("stock=stock-1").eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0).update();
        if (!success) {
            seckillReservationService.transition(
                    voucherOrder.getId(),
                    SeckillReservationService.RESERVED,
                    SeckillReservationService.RELEASE_REQUIRED,
                    "DB_STOCK_NOT_ENOUGH"
            );
            return OrderProcessingResult.terminal("DB_STOCK_NOT_ENOUGH", "Database stock is exhausted");
        }
        LocalDateTime now = LocalDateTime.now();
        voucherOrder.setOrderStatus(OrderStatus.UNPAID);
        voucherOrder.setVersion(0);
        voucherOrder.setCreateTime(now);
        voucherOrder.setUpdateTime(now);
        save(voucherOrder);
        domainEventService.appendOrderCreated(voucherOrder);
        if (!seckillReservationService.transition(
                voucherOrder.getId(),
                SeckillReservationService.RESERVED,
                SeckillReservationService.ORDER_CREATED,
                null
        )) {
            throw new IllegalStateException("Reservation state changed while creating order " + voucherOrder.getId());
        }
        operationLogService.record("order", "order_create", "voucher_order", voucherOrder.getId(), String.valueOf(voucherOrder.getId()), null, voucherOrder, true, null);
        return OrderProcessingResult.created();
    }

    private boolean confirmRedisReservation(VoucherOrder order) {
        String reservationKey = SECKILL_RESERVATION_KEY + order.getId();
        Long result = stringRedisTemplate.execute(
                CONFIRM_RESERVATION_SCRIPT,
                List.of(reservationKey)
        );
        if (result != null && result == 0L) {
            Map<String, String> hotMirror = Map.of(
                    "orderId", String.valueOf(order.getId()),
                    "voucherId", String.valueOf(order.getVoucherId()),
                    "userId", String.valueOf(order.getUserId()),
                    "status", SeckillReservationService.RESERVED
            );
            stringRedisTemplate.opsForHash().putAll(reservationKey, hotMirror);
            stringRedisTemplate.expire(reservationKey, Duration.ofDays(7));
            result = stringRedisTemplate.execute(CONFIRM_RESERVATION_SCRIPT, List.of(reservationKey));
        }
        boolean confirmed = result != null && result == 1L;
        recordReservationMetric("confirm", confirmed);
        return confirmed;
    }

    private boolean releaseRedisReservation(VoucherOrder order, String failureCode) {
        Long result = stringRedisTemplate.execute(
                RELEASE_RESERVATION_SCRIPT,
                List.of(
                        SECKILL_RESERVATION_KEY + order.getId(),
                        SECKILL_STOCK_KEY + order.getVoucherId(),
                        SECKILL_ORDER_KEY + order.getVoucherId()
                ),
                String.valueOf(order.getUserId())
        );
        if (result != null && result == 1L) {
            SeckillReservation reservation = seckillReservationService.find(order.getId());
            if (reservation != null && SeckillReservationService.RELEASE_REQUIRED.equals(reservation.getStatus())) {
                seckillReservationService.transition(
                        order.getId(),
                        SeckillReservationService.RELEASE_REQUIRED,
                        SeckillReservationService.RELEASED,
                        failureCode
                );
            }
            recordReservationMetric("release", true);
            return true;
        }
        recordReservationMetric("release", false);
        return false;
    }

    private void recordReservationMetric(String operation, boolean success) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("citycopilot.seckill.reservation.operation")
                .tag("operation", operation)
                .tag("result", success ? "success" : "failure")
                .register(meterRegistry)
                .increment();
    }

    private void recordOutcome(OrderProcessingResult result) {
        if (meterRegistry == null || result == null) {
            return;
        }
        Counter.builder("citycopilot.seckill.processing.outcome")
                .tag("outcome", result.outcome().name())
                .tag("code", result.code())
                .register(meterRegistry)
                .increment();
    }

    private static String streamConsumerName() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            host = "unknown-host";
        }
        String runtime = ManagementFactory.getRuntimeMXBean().getName().replaceAll("[^A-Za-z0-9_.-]", "_");
        return host + "-" + runtime + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private int normalizePayType(Integer payType) {
        if (payType == null) {
            return 1;
        }
        return payType >= 1 && payType <= 3 ? payType : 0;
    }

    private boolean transition(
            VoucherOrder order,
            OrderStatus target,
            Long userId,
            java.util.function.Consumer<LambdaUpdateWrapper<VoucherOrder>> changes
    ) {
        OrderStatus source = order.orderStatus();
        source.requireTransitionTo(target);
        LambdaUpdateWrapper<VoucherOrder> wrapper = new LambdaUpdateWrapper<VoucherOrder>()
                .eq(VoucherOrder::getId, order.getId())
                .eq(userId != null, VoucherOrder::getUserId, userId)
                .eq(order.getVersion() != null, VoucherOrder::getVersion, order.getVersion())
                .set(VoucherOrder::getStatus, target.code())
                .setSql("version = version + 1")
                .set(VoucherOrder::getUpdateTime, LocalDateTime.now());
        if (order.getStatus() == null) {
            wrapper.isNull(VoucherOrder::getStatus);
        } else {
            wrapper.eq(VoucherOrder::getStatus, source.code());
        }
        changes.accept(wrapper);
        return baseMapper.update(null, wrapper) == 1;
    }

    private void restoreSeckillInventory(Long voucherId, Long userId, boolean releaseUserEligibility) {
        if (voucherId == null) {
            return;
        }
        seckillVoucherService.update()
                .setSql("stock=stock+1")
                .eq("voucher_id", voucherId)
                .update();
        stringRedisTemplate.opsForValue().increment(SECKILL_STOCK_KEY + voucherId);
        if (releaseUserEligibility && userId != null) {
            stringRedisTemplate.opsForSet().remove(SECKILL_ORDER_KEY + voucherId, userId.toString());
        }
    }

    private boolean isSeckillWorkerReady() {
        return streamWorkerEnabled && redissonClient != null;
    }

    private void ensureRedisStock(Long voucherId, Integer stock) {
        try {
            String key = SECKILL_STOCK_KEY + voucherId;
            Boolean exists = stringRedisTemplate.hasKey(key);
            if (!Boolean.TRUE.equals(exists)) {
                stringRedisTemplate.opsForValue().setIfAbsent(key, String.valueOf(stock));
            }
        } catch (Exception error) {
            log.debug("Prepare seckill stock skipped: " + error.getMessage());
        }
    }

    private void recordOrderChange(String action, Long orderId, VoucherOrder before, boolean success, String errorMessage) {
        VoucherOrder after = orderId == null ? null : getById(orderId);
        operationLogService.record("order", action, "voucher_order", orderId, String.valueOf(orderId), before, after, success, errorMessage);
    }
}
