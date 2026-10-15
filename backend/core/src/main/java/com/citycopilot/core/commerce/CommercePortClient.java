package com.citycopilot.core.commerce;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.core.JacksonException;

import com.citycopilot.contract.commerce.CommerceAdmissionOutcomePolicy;
import com.citycopilot.contract.commerce.CommerceContracts.AdmissionAccepted;
import com.citycopilot.contract.commerce.CommerceContracts.CommerceError;
import com.citycopilot.contract.commerce.CommerceContracts.ConsistencyRepairRequest;
import com.citycopilot.contract.commerce.CommerceContracts.ConsistencyRepairResult;
import com.citycopilot.contract.commerce.CommerceContracts.OrderActionRequest;
import com.citycopilot.contract.commerce.CommerceContracts.OrderSummary;
import com.citycopilot.contract.commerce.CommerceContracts.OrderView;
import com.citycopilot.contract.commerce.CommerceContracts.VoucherView;
import com.citycopilot.contract.commerce.CommerceContracts.VoucherCreated;
import com.citycopilot.contract.commerce.CommerceContracts.OperationsSummary;
import com.citycopilot.contract.commerce.CommerceContracts.DeadLetterView;
import com.citycopilot.contract.commerce.CommerceContracts.DeadLetterReplayResult;
import com.citycopilot.contract.commerce.CommerceContracts.PaymentReview;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.UserDTO;
import com.citycopilot.core.config.CrossServiceHttpConfig.CommerceHttpFactories;
import com.citycopilot.core.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Core 到 Commerce 服务的版本化 HTTP 端口客户端。
 *
 * <p>它透传当前用户与服务令牌、适配 Core 的 {@link Result}，并把 Commerce 作为券和订单的唯一写入方。</p>
 */
@Component
public class CommercePortClient {

    /** Core 与 Commerce 之间的内部服务认证请求头。 */
    private static final String SERVICE_TOKEN = "X-CityCopilot-Service-Token";

    /** 透传终端用户主键的请求头。 */
    private static final String USER_ID = "X-CityCopilot-User-Id";

    /** 秒杀结果不确定时建议客户端等待的秒数。 */
    private static final String RETRY_AFTER_ONE_SECOND = "1";

    private static final JsonMapper ERROR_MAPPER = JsonMapper.builderWithJackson2Defaults()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    /** 预配置基础地址与超时的同步 HTTP 客户端。 */
    private final RestClient client;

    /** 发往 Commerce 的内部服务令牌。 */
    private final String token;

    /**
     * 创建生产 HTTP 客户端；连接与读取超时保持较短，避免占满 Core 请求线程。
     *
     * @param baseUrl Commerce 服务基础地址
     * @param token 内部服务令牌
     */
    @Autowired
    public CommercePortClient(
            @Value("${citycopilot.commerce.base-url:http://127.0.0.1:8083}") String baseUrl,
            @Value("${citycopilot.commerce.service-token:}") String token,
            CommerceHttpFactories factories
    ) {
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factories.commerce())
                .build();
        this.token = token;
    }

    /**
     * 注入自定义 RestClient 的兼容构造器，供端口级测试使用。
     *
     * @param client 已配置客户端
     * @param token 内部服务令牌
     */
    CommercePortClient(RestClient client, String token) {
        this.client = client;
        this.token = token;
    }

    /**
     * 为当前用户提交一次秒杀准入请求。
     *
     * <p>网络异常时准入结果可能未知，因此抛出带重试信息的异常，调用方必须复用同一幂等键。</p>
     *
     * @param voucherId 秒杀券主键
     * @param idempotencyKey 同一次业务请求的幂等键
     * @return Commerce 返回的订单主键和准入码
     * @throws CommerceHttpException Commerce 返回业务错误或准入结果无法确定时抛出
     */
    public Result seckill(Long voucherId, String idempotencyKey) {
        try {
            AdmissionAccepted response = client.post()
                    .uri("/commerce/v1/seckill/{voucherId}", voucherId)
                    .headers(headers -> headers(headers, userId()))
                    .headers(headers -> {
                        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                            headers.set("Idempotency-Key", idempotencyKey);
                        }
                    })
                    .retrieve()
                    .body(AdmissionAccepted.class);
            Result result = Result.ok(response == null ? null : response.orderId());
            if (response != null && response.code() != null) {
                result.setCode(response.code());
            }
            return result;
        } catch (RestClientResponseException error) {
            throw seckillFailure(error);
        } catch (RestClientException error) {
            throw new CommerceHttpException(
                    503,
                    CommerceAdmissionOutcomePolicy.COMMERCE_ADMISSION_OUTCOME_UNKNOWN,
                    sameIdempotencyKeyRetryMessage("Admission outcome is unknown"),
                    RETRY_AFTER_ONE_SECOND,
                    error
            );
        }
    }

    /**
     * 查询当前用户的订单页。
     *
     * @param page 从 1 开始的页码，空值按第一页处理
     * @return 订单列表；长整型订单主键已转为字符串以避免前端精度丢失
     */
    public Result myOrders(Integer page) {
        try {
            List<OrderSummary> response = client.get()
                    .uri(builder -> builder.path("/commerce/v1/orders")
                            .queryParam("page", page == null ? 1 : page)
                            .build())
                    .headers(headers -> headers(headers, userId()))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return Result.ok(response == null ? List.of() : response);
        } catch (RestClientResponseException error) {
            throw failure(error);
        } catch (RestClientException error) {
            return unavailable();
        }
    }

    /**
     * 查询单个订单。
     *
     * @param orderId 订单主键
     * @param admin 是否使用管理员可见性规则
     * @return 订单详情或稳定失败结果
     */
    public Result order(Long orderId, boolean admin) {
        try {
            OrderView response = client.get()
                    .uri(builder -> builder.path("/commerce/v1/orders/{id}")
                            .queryParam("admin", admin && admin())
                            .queryParam("merchant", admin && !admin())
                            .build(orderId))
                    .headers(headers -> headers(headers, userId()))
                    .retrieve()
                    .body(OrderView.class);
            return Result.ok(response);
        } catch (RestClientResponseException error) {
            throw failure(error);
        } catch (RestClientException error) {
            return unavailable();
        }
    }

    /**
     * 调用 Commerce 订单状态机执行取消、支付、核销或退款。
     *
     * @param orderId 订单主键
     * @param action Commerce 状态机动作名
     * @param payType 支付方式编码；非支付动作可为空
     * @param admin 是否以管理员操作视角调用
     * @return 状态迁移后的订单
     */
    public Result transition(Long orderId, String action, Integer payType, boolean admin) {
        try {
            OrderView response = client.post()
                    .uri(builder -> builder.path("/commerce/v1/orders/{id}/actions")
                            .queryParam("admin", admin && admin())
                            .queryParam("merchant", admin && !admin())
                            .build(orderId))
                    .headers(headers -> headers(headers, userId()))
                    .body(new OrderActionRequest(action, payType == null ? 1 : payType))
                    .retrieve()
                    .body(OrderView.class);
            return Result.ok(response);
        } catch (RestClientResponseException error) {
            throw failure(error);
        } catch (RestClientException error) {
            return unavailable();
        }
    }

    /**
     * 查询券列表。
     *
     * @param shopId 可选店铺过滤条件
     * @param admin 是否查询后台可见的全部状态
     * @param keyword 可选名称关键词
     * @param status 可选券状态
     * @return 券列表或稳定失败结果
     */
    public Result vouchers(Long shopId, boolean admin, String keyword, Integer status) {
        try {
            List<VoucherView> response = client.get()
                    .uri(builder -> {
                        builder.path("/commerce/v1/vouchers").queryParam("admin", admin);
                        if (shopId != null) builder.queryParam("shopId", shopId);
                        if (keyword != null && !keyword.isBlank()) builder.queryParam("keyword", keyword);
                        if (status != null) builder.queryParam("status", status);
                        return builder.build();
                    })
                    .headers(headers -> headers(headers, userId()))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return Result.ok(response == null ? List.of() : response);
        } catch (RestClientResponseException error) {
            throw failure(error);
        } catch (RestClientException error) {
            return unavailable();
        }
    }

    /**
     * 以当前管理员身份创建券。
     *
     * @param voucher Commerce 接受的券请求体
     * @return 新券主键
     */
    public Result createVoucher(Map<String, Object> voucher) {
        try {
            VoucherCreated response = client.post()
                    .uri("/commerce/v1/vouchers")
                    .headers(headers -> {
                        headers(headers, userId());
                        headers.set("X-CityCopilot-Admin", String.valueOf(admin()));
                    })
                    .body(voucher)
                    .retrieve()
                    .body(VoucherCreated.class);
            return Result.ok(response == null ? null : response.id());
        } catch (RestClientResponseException error) {
            throw failure(error);
        } catch (RestClientException error) {
            return unavailable();
        }
    }

    /**
     * 以当前管理员身份更新券。
     *
     * @param voucherId 券主键
     * @param voucher 稀疏更新请求体
     * @return Commerce 更新结果
     */
    public Result updateVoucher(Long voucherId, Map<String, Object> voucher) {
        try {
            VoucherView response = client.post()
                    .uri("/commerce/v1/vouchers/{voucherId}", voucherId)
                    .headers(headers -> {
                        headers(headers, userId());
                        headers.set("X-CityCopilot-Admin", String.valueOf(admin()));
                    })
                    .body(voucher)
                    .retrieve()
                    .body(VoucherView.class);
            return Result.ok(response);
        } catch (RestClientResponseException error) {
            throw failure(error);
        } catch (RestClientException error) {
            return unavailable();
        }
    }

    /**
     * 采集单张券的一致性证据。
     *
     * @param voucherId 秒杀券主键
     * @return MySQL、Redis、Reservation、订单与 Outbox 快照
     */
    public Map<String, Object> consistency(Long voucherId) {
        return client.get()
                .uri("/commerce/v1/consistency/{voucherId}", voucherId)
                .headers(headers -> headers(headers, userId()))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    /**
     * 基于仍匹配的证据哈希请求 Commerce 修复 Redis 库存。
     *
     * @param voucherId 秒杀券主键
     * @param evidenceHash 预览阶段取得的证据哈希
     * @return 修复结果
     */
    public ConsistencyRepairResult repairConsistency(Long voucherId, String evidenceHash) {
        return client.post()
                .uri("/commerce/v1/consistency/{voucherId}/repair", voucherId)
                .headers(headers -> headers(headers, userId()))
                .body(new ConsistencyRepairRequest(evidenceHash))
                .retrieve()
                .body(ConsistencyRepairResult.class);
    }

    /**
     * 重新采集证据并验证一致性后置条件。
     *
     * @param voucherId 秒杀券主键
     * @return 最新验证结果
     */
    public Map<String, Object> verifyConsistency(Long voucherId) {
        return client.get()
                .uri("/commerce/v1/consistency/{voucherId}/verify", voucherId)
                .headers(headers -> headers(headers, userId()))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    public List<PaymentReview> paymentIssues() {
        return client.get().uri("/commerce/v1/ops/payments").headers(this::opsHeaders)
                .retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public PaymentReview paymentReview(String paymentId) {
        return client.get().uri("/commerce/v1/ops/payments/{paymentId}", paymentId).headers(this::opsHeaders)
                .retrieve().body(PaymentReview.class);
    }

    public PaymentReview repairPayment(String paymentId, String evidenceHash) {
        return client.post().uri("/commerce/v1/ops/payments/{paymentId}/repair", paymentId).headers(this::opsHeaders)
                .body(new ConsistencyRepairRequest(evidenceHash)).retrieve().body(PaymentReview.class);
    }

    /**
     * 查询指定时间窗口内的 Commerce 运维摘要。
     *
     * @param fromInclusive 起始时间，包含
     * @param toExclusive 结束时间，不包含
     * @return 运维指标摘要
     */
    public Result operationsSummary(Instant fromInclusive, Instant toExclusive) {
        try {
            OperationsSummary response = client.get()
                    .uri(builder -> builder.path("/commerce/v1/ops/summary")
                            .queryParam("from", fromInclusive)
                            .queryParam("to", toExclusive)
                            .build())
                    .headers(headers -> headers(headers, userId()))
                    .retrieve()
                    .body(OperationsSummary.class);
            return Result.ok(response);
        } catch (RestClientResponseException error) {
            throw failure(error);
        } catch (RestClientException error) {
            return unavailable();
        }
    }

    /**
     * 查询近期 Commerce 秒杀死信。
     *
     * @param limit 最大条数
     * @return 死信记录
     * @throws IllegalStateException Commerce 运维接口不可用时抛出
     */
    public List<DeadLetterView> deadLetters(Integer limit) {
        try {
            List<DeadLetterView> response = client.get()
                    .uri(builder -> builder.path("/commerce/v1/ops/dead-letters")
                            .queryParam("limit", limit == null ? 20 : limit)
                            .build())
                    .headers(this::opsHeaders)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return response == null ? List.of() : response;
        } catch (RestClientException error) {
            throw new IllegalStateException("Commerce dead-letter query unavailable", error);
        }
    }

    /**
     * 请求 Commerce 幂等重放一条死信。
     *
     * @param sourceId 死信记录源主键
     * @return 重放结果
     * @throws IllegalStateException Commerce 运维接口不可用时抛出
     */
    public DeadLetterReplayResult replayDeadLetter(String sourceId) {
        try {
            return client.post()
                    .uri("/commerce/v1/ops/dead-letters/{sourceId}/replay", sourceId)
                    .headers(this::opsHeaders)
                    .retrieve()
                    .body(DeadLetterReplayResult.class);
        } catch (RestClientException error) {
            throw new IllegalStateException("Commerce dead-letter replay unavailable", error);
        }
    }

    /**
     * 写入 Commerce 普通接口需要的服务令牌和用户身份。
     *
     * @param headers 请求头
     * @param userId 当前用户主键；无用户时为负值
     */
    private void headers(HttpHeaders headers, long userId) {
        headers.set(SERVICE_TOKEN, token);
        headers.set(USER_ID, String.valueOf(userId));
    }

    /**
     * 为高风险运维接口补充授权结论和审计主体。
     *
     * @param headers 请求头
     */
    private void opsHeaders(HttpHeaders headers) {
        headers(headers, userId());
        headers.set("X-CityCopilot-Ops-Authorized", String.valueOf(opsAuthorized()));
        headers.set("X-CityCopilot-Actor", actor());
    }

    /** @return 当前线程用户主键；没有用户时返回 {@code -1} */
    private long userId() {
        UserDTO user = UserHolder.getUser();
        return user == null || user.getId() == null ? -1L : user.getId();
    }

    /** @return 当前线程用户是否为管理员 */
    private boolean admin() {
        UserDTO user = UserHolder.getUser();
        return user != null && "admin".equalsIgnoreCase(user.getRole());
    }

    /**
     * 按角色或显式权限判断当前主体能否调用 Commerce 运维接口。
     *
     * @return 是否同时具备管理访问和订单写权限
     */
    private boolean opsAuthorized() {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return false;
        }
        if ("admin".equalsIgnoreCase(user.getRole())) {
            return true;
        }
        java.util.Set<String> permissions = user.getPermissions();
        return permissions != null
                && (permissions.contains("*")
                || (permissions.contains("admin:access") && permissions.contains("order:write")));
    }

    /** @return 用于 Commerce 审计的稳定用户、服务或角色标识 */
    private String actor() {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return "unknown";
        }
        if (user.getId() != null) {
            return "user:" + user.getId();
        }
        if (user.getNickName() != null && !user.getNickName().isBlank()) {
            return "service:" + user.getNickName();
        }
        return "role:" + String.valueOf(user.getRole());
    }

    /**
     * 保留 Commerce 的 HTTP 状态、错误码和 Retry-After，由全局异常处理写回 Core 调用方。
     *
     * @param error HTTP 响应异常
     * @return 携带上游状态的异常
     */
    private CommerceHttpException failure(RestClientResponseException error) {
        CommerceError body = errorBody(error);
        String retryAfter = error.getResponseHeaders() == null ? null
                : error.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        return new CommerceHttpException(error.getStatusCode().value(), body.code(), body.message(), retryAfter, error);
    }

    private CommerceError errorBody(RestClientResponseException error) {
        String unreadable = "Commerce returned HTTP " + error.getStatusCode().value()
                + " without a readable error body; inspect Commerce logs and query any earlier write result before retrying";
        try {
            CommerceError body = ERROR_MAPPER.readValue(error.getResponseBodyAsByteArray(), CommerceError.class);
            if (body != null) {
                return new CommerceError(false,
                        body.code() == null ? "COMMERCE_UNAVAILABLE" : body.code(),
                        body.message() == null ? unreadable : body.message());
            }
        } catch (JacksonException unreadableBody) {
            // 上游已明确返回 HTTP 失败；解码失败不能抹掉该状态，也不证明先前写请求没有执行。
        }
        return new CommerceError(false, "COMMERCE_UNAVAILABLE", unreadable);
    }

    /** @return Commerce 网络不可达时使用的统一失败结果 */
    private Result unavailable() {
        return Result.fail("COMMERCE_UNAVAILABLE",
                "Commerce request could not be completed; check service connectivity and query any earlier write result before retrying");
    }

    /**
     * 保留秒杀上游 HTTP 状态、错误码和可重试语义。
     *
     * @param error Commerce HTTP 响应异常
     * @return 控制层可据此返回状态码与 Retry-After 的异常
     */
    private CommerceHttpException seckillFailure(RestClientResponseException error) {
        CommerceError body = errorBody(error);
        String code = body.code();
        String message = body.message();
        boolean retryableOutcome = CommerceAdmissionOutcomePolicy.isRetryable(code);
        if (retryableOutcome) {
            message = sameIdempotencyKeyRetryMessage(message);
        }
        String retryAfter = error.getResponseHeaders() == null ? null
                : error.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        if ((retryAfter == null || retryAfter.isBlank()) && retryableOutcome) retryAfter = RETRY_AFTER_ONE_SECOND;
        return new CommerceHttpException(
                error.getStatusCode().value(),
                code,
                message,
                retryAfter,
                error
        );
    }

    /**
     * 为结果不确定的准入错误补充“复用同一幂等键”指引。
     *
     * @param message 原始 Commerce 错误信息
     * @return 不重复追加指引的错误信息
     */
    private static String sameIdempotencyKeyRetryMessage(String message) {
        String normalized = message == null || message.isBlank()
                ? "Admission outcome is unknown"
                : message.trim();
        if (normalized.toLowerCase(java.util.Locale.ROOT).contains("idempotency-key")) {
            return normalized;
        }
        return normalized + "; retry with the same Idempotency-Key";
    }

    /**
     * 秒杀端口异常，携带需要透传给 HTTP 客户端的状态、业务码和重试等待时间。
     */
    public static final class CommerceHttpException extends RuntimeException {

        /** 应返回给 Core 调用方的 HTTP 状态码。 */
        private final int status;

        /** 稳定 Commerce 业务错误码。 */
        private final String code;

        /** 可选 Retry-After 秒数或 HTTP 日期。 */
        private final String retryAfter;

        /**
         * @param status HTTP 状态码
         * @param code 业务错误码
         * @param message 可读错误信息
         * @param retryAfter 可选 Retry-After 秒数或 HTTP 日期
         * @param cause 原始客户端异常
         */
        public CommerceHttpException(
                int status,
                String code,
                String message,
                String retryAfter,
                Throwable cause
        ) {
            super(message, cause);
            this.status = status;
            this.code = code;
            this.retryAfter = retryAfter;
        }

        /** @return HTTP 状态码 */
        public int status() {
            return status;
        }

        /** @return 稳定业务错误码 */
        public String code() {
            return code;
        }

        /** @return 上游 Retry-After（秒数或 HTTP 日期）；未提供时可能为空，不代表写操作可自动重试 */
        public String retryAfter() {
            return retryAfter;
        }
    }
}
