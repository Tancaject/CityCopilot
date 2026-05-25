package com.citycopilot.core.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.VoucherOrderDTO;
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
import com.citycopilot.core.utils.RedisIdWorker;
import com.citycopilot.core.utils.SystemConstants;
import com.citycopilot.core.utils.UserHolder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Lazy;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.citycopilot.core.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.citycopilot.core.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
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
    @Lazy
    private IVoucherOrderService voucherOrderService;  // 自注入，解决异步线程代理问题

    @Value("${citycopilot.seckill.stream-worker.enabled:true}")
    private boolean streamWorkerEnabled;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("lua/seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    /*阻塞队列:
        入队时，如果队列已满，线程会被阻塞，直到队列有空间。
        出队时，如果队列为空，线程会被阻塞，直到队列中有元素。
    */
    private static final ExecutorService SECKILL_ORDER_EXECUTOR= Executors.newSingleThreadExecutor();//创建线程

    @PostConstruct //当前类初始化完成后立即执行该方法
    private void init(){
        if (!isSeckillWorkerReady()) {
            if (streamWorkerEnabled) {
                log.warn("Seckill stream worker is disabled because Redisson is not available");
            }
            return;
        }
        SECKILL_ORDER_EXECUTOR.submit(new voucherOrderHandler());
    }
    //创建线程任务
    private class voucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while (true) {
                try {
                    // 从消息队列中获取订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
                    );
                    //判断消息是否获取成功
                    if (list.isEmpty()||list==null){
                        //获取失败，继续循环
                        continue;
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 获取成功则下单
                    handleVoucherOrder(voucherOrder);
                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge("stream.orders","g1",record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {//处理pending list里的消息
            while (true) {
                try {
                    // 从pending list中获取订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create("stream.orders", ReadOffset.from("0"))
                    );
                    //判断消息是否获取成功
                    if (list.isEmpty()||list==null){
                        //获取失败，说明pending list里没有消息了，结束
                        break;
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 获取成功则下单
                    handleVoucherOrder(voucherOrder);
                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge("stream.orders","g1",record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    private IVoucherOrderService proxy;

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId=voucherOrder.getUserId();
        if (redissonClient == null) {
            throw new IllegalStateException("Redisson is required for async seckill order handling");
        }
        //创建锁对象
        RLock rLock=redissonClient.getLock("lock:order:"+userId);//redisson的锁
        //获取锁
        boolean isLock=rLock.tryLock();
        //判断锁是否获取成功
        if (!isLock){//获取不成功
            //返回错误
            log.error("不允许重复下单");
            return;
        }
        //对用户id加锁 并确保在事务提交后再释放锁
        try {
            voucherOrderService.createVoucherOrder(voucherOrder);  // 用自注入的代理对象
        }  finally {
            rLock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId){
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
        long orderId = redisIdWorker.nextId("order");
        //执行lua脚本
        Long result=stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),//该脚本不需要传key，因此传一个空集合(不能传null)
                voucherId.toString(), userId.toString(), String.valueOf(orderId)  //转成String
        );
        //判断结果是否为0
        int res=result.intValue();
        if (res!=0) {
            //不为0.没有购买资格
            return Result.fail(res==1 ? "SECKILL_STOCK_NOT_ENOUGH" : "SECKILL_DUPLICATE_ORDER", res==1?"库存不足！":"不能重复下单！");
        }
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }

    @Override
    public Result queryMyOrders(Integer current) {
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
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = query()
                .eq("id", orderId)
                .eq("user_id", userId)
                .one();
        if (order == null) {
            return Result.ok(processingOrder(orderId));
        }
        return Result.ok(buildOrderViews(List.of(order)).get(0));
    }

    @Override
    public Result queryOrderForAdmin(Long orderId) {
        VoucherOrder order = getById(orderId);
        if (order == null) {
            return Result.fail("ORDER_NOT_FOUND", "订单不存在");
        }
        return Result.ok(orderView(order));
    }

    @Override
    @Transactional
    public Result cancelOrder(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = query()
                .eq("id", orderId)
                .eq("user_id", userId)
                .one();
        if (order == null) {
            return Result.fail("ORDER_NOT_FOUND", "订单不存在");
        }
        Integer status = order.getStatus();
        if (status != null && status != 1) {
            return Result.fail("ORDER_STATUS_INVALID", "当前订单状态不可取消");
        }
        boolean updated = update()
                .eq("id", orderId)
                .eq("user_id", userId)
                .and(wrapper -> wrapper.eq("status", 1).or().isNull("status"))
                .set("status", 4)
                .set("update_time", LocalDateTime.now())
                .update();
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
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = query()
                .eq("id", orderId)
                .eq("user_id", userId)
                .one();
        if (order == null) {
            return Result.fail("ORDER_NOT_FOUND", "订单不存在");
        }
        Integer status = order.getStatus();
        if (status != null && status != 1) {
            return Result.fail("ORDER_STATUS_INVALID", "当前订单状态不可支付");
        }
        int payTypeValue = normalizePayType(payType);
        if (payTypeValue == 0) {
            return Result.fail("ORDER_PAY_TYPE_INVALID", "支付方式不支持");
        }
        LocalDateTime now = LocalDateTime.now();
        boolean updated = update()
                .eq("id", orderId)
                .eq("user_id", userId)
                .and(wrapper -> wrapper.eq("status", 1).or().isNull("status"))
                .set("status", 2)
                .set("pay_type", payTypeValue)
                .set("pay_time", now)
                .set("update_time", now)
                .update();
        if (!updated) {
            return Result.fail("ORDER_STATUS_CHANGED", "订单状态已变化，请刷新后重试");
        }
        recordOrderChange("order_pay", orderId, order, true, null);
        return queryOrder(orderId);
    }

    @Override
    @Transactional
    public Result useOrder(Long orderId) {
        VoucherOrder order = getById(orderId);
        if (order == null) {
            return Result.fail("ORDER_NOT_FOUND", "订单不存在");
        }
        if (order.getStatus() == null || order.getStatus() != 2) {
            return Result.fail("ORDER_STATUS_INVALID", "当前订单状态不可核销");
        }
        LocalDateTime now = LocalDateTime.now();
        boolean updated = update()
                .eq("id", orderId)
                .eq("status", 2)
                .set("status", 3)
                .set("use_time", now)
                .set("update_time", now)
                .update();
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
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = query()
                .eq("id", orderId)
                .eq("user_id", userId)
                .one();
        if (order == null) {
            return Result.fail("ORDER_NOT_FOUND", "订单不存在");
        }
        if (order.getStatus() == null || order.getStatus() != 2) {
            return Result.fail("ORDER_STATUS_INVALID", "当前订单状态不可退款");
        }
        LocalDateTime now = LocalDateTime.now();
        boolean updated = update()
                .eq("id", orderId)
                .eq("user_id", userId)
                .eq("status", 2)
                .set("status", 6)
                .set("refund_time", now)
                .set("update_time", now)
                .update();
        if (!updated) {
            return Result.fail("ORDER_STATUS_CHANGED", "订单状态已变化，请刷新后重试");
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
        view.setState(orderState(order.getStatus()));
        view.setStatusText(orderStatusText(order.getStatus()));
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

    private VoucherOrderDTO processingOrder(Long orderId) {
        VoucherOrderDTO view = new VoucherOrderDTO();
        view.setId(orderId);
        view.setState("processing");
        view.setStatusText("订单处理中");
        return view;
    }

    private String orderState(Integer status) {
        if (status == null || status == 1) {
            return "unpaid";
        }
        return switch (status) {
            case 2 -> "paid";
            case 3 -> "used";
            case 4 -> "cancelled";
            case 5 -> "refunding";
            case 6 -> "refunded";
            default -> "unknown";
        };
    }

    private String orderStatusText(Integer status) {
        if (status == null || status == 1) {
            return "待支付";
        }
        return switch (status) {
            case 2 -> "已支付";
            case 3 -> "已核销";
            case 4 -> "已取消";
            case 5 -> "退款中";
            case 6 -> "已退款";
            default -> "状态待确认";
        };
    }

    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder){
        //确保一人只能一单
        Long userId= voucherOrder.getUserId();  // 从订单对象获取，不能用UserHolder（异步线程拿不到）
        Long count = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherOrder.getVoucherId())
                .and(wrapper -> wrapper.isNull("status").or().notIn("status", 4, 6))
                .count();
        if (count > 0) {
            log.error("用户已经购买过一次");
            return;
        }
        //5、扣除库存
        boolean success = seckillVoucherService.update().setSql("stock=stock-1").eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0).update();
        if (!success) {
            log.error("库存不足 ");
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        voucherOrder.setStatus(1);
        voucherOrder.setCreateTime(now);
        voucherOrder.setUpdateTime(now);
        save(voucherOrder);
        operationLogService.record("order", "order_create", "voucher_order", voucherOrder.getId(), String.valueOf(voucherOrder.getId()), null, voucherOrder, true, null);
    }

    private int normalizePayType(Integer payType) {
        if (payType == null) {
            return 1;
        }
        return payType >= 1 && payType <= 3 ? payType : 0;
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
