package com.citycopilot.core.controller;


import com.citycopilot.core.commerce.CommercePortClient;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.security.PermissionCodes;
import com.citycopilot.core.security.RequirePermission;
import com.citycopilot.core.service.IVoucherOrderService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;

import java.net.URI;

/**
 * 优惠券订单兼容控制器。
 *
 * <p>保留 Core 对前端的既有 URL 与响应形状，订单准入、查询和状态流转均通过
 * {@link IVoucherOrderService} 委托给 Commerce 单写者；Core 不直接修改订单数据。</p>
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {
    /** Commerce 端口适配服务，负责身份透传和响应映射。 */
    private final IVoucherOrderService voucherOrderService;

    /**
     * @param voucherOrderService 优惠券订单的 Commerce 端口适配服务
     */
    public VoucherOrderController(IVoucherOrderService voucherOrderService) {
        this.voucherOrderService = voucherOrderService;
    }

    /**
     * 向 Commerce 提交秒杀订单准入请求。
     *
     * <p>异步受理返回 HTTP 202；若响应携带订单号，同时设置可查询订单的 Location。</p>
     *
     * @param voucherId 秒杀券主键
     * @param idempotencyKey 可选业务幂等键，同一次重试应保持一致
     * @return Commerce 准入结果及对应 HTTP 状态
     */
    @PostMapping("seckill/{id}")
    public ResponseEntity<Result> seckillVoucher(
            @PathVariable("id") Long voucherId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        Result result = voucherOrderService.seckillVoucher(voucherId, idempotencyKey);
        HttpStatus status = "COMMERCE_ORDER_ACCEPTED".equals(result.getCode())
                ? HttpStatus.ACCEPTED
                : HttpStatus.OK;
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
        if (result.getData() != null) {
            response.location(URI.create("/voucher-order/" + result.getData()));
        }
        return response.body(result);
    }

    /**
     * 将 Commerce HTTP 失败映射回 Core 的稳定错误协议。
     *
     * <p>保留上游状态码及 {@code Retry-After}，让限流或暂时不可用时的客户端退避语义不丢失。</p>
     *
     * @param error Commerce 端口调用异常
     * @return 保留重试提示的统一失败响应
     */
    @ExceptionHandler(CommercePortClient.CommerceHttpException.class)
    ResponseEntity<Result> commerceFailure(CommercePortClient.CommerceHttpException error) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(error.status());
        if (error.retryAfter() != null && !error.retryAfter().isBlank()) {
            response.header(HttpHeaders.RETRY_AFTER, error.retryAfter());
        }
        return response.body(Result.fail(error.code(), error.getMessage()));
    }

    /**
     * 分页查询当前登录用户的订单。
     *
     * @param current 页码，默认第 1 页
     * @return Commerce 返回的本人订单分页结果
     */
    @GetMapping("of/me")
    public Result queryMyOrders(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return voucherOrderService.queryMyOrders(current);
    }

    /**
     * 查询当前用户可见的订单详情。
     *
     * @param orderId 订单主键
     * @return 订单详情或 Commerce 的不存在、无权访问结果
     */
    @GetMapping("{id}")
    public Result queryOrder(@PathVariable("id") Long orderId) {
        return voucherOrderService.queryOrder(orderId);
    }

    /**
     * 请求 Commerce 取消当前用户的订单。
     *
     * @param orderId 订单主键
     * @return 状态流转结果；所有权和可取消状态由 Commerce 校验
     */
    @PostMapping("{id}/cancel")
    public Result cancelOrder(@PathVariable("id") Long orderId) {
        return voucherOrderService.cancelOrder(orderId);
    }

    /**
     * 请求 Commerce 将订单流转为已支付。
     *
     * @param orderId 订单主键
     * @param payType 支付方式编码，默认 {@code 1}
     * @return 支付状态流转结果
     */
    @PostMapping("{id}/pay")
    public Result payOrder(
            @PathVariable("id") Long orderId,
            @RequestParam(value = "payType", defaultValue = "1") Integer payType
    ) {
        return voucherOrderService.payOrder(orderId, payType);
    }

    /**
     * 以订单写权限核销指定订单。
     *
     * @param orderId 订单主键
     * @return Commerce 的核销状态流转结果
     */
    @PostMapping("{id}/use")
    @RequirePermission(PermissionCodes.ORDER_WRITE)
    public Result useOrder(@PathVariable("id") Long orderId) {
        return voucherOrderService.useOrder(orderId);
    }

    /**
     * 请求 Commerce 对当前用户的订单执行退款流转。
     *
     * @param orderId 订单主键
     * @return 退款状态流转结果；合法前置状态由 Commerce 校验
     */
    @PostMapping("{id}/refund")
    public Result refundOrder(@PathVariable("id") Long orderId) {
        return voucherOrderService.refundOrder(orderId);
    }
}
