package com.citycopilot.core.commerce;

import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.UserDTO;
import com.citycopilot.core.utils.UserHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class CommercePortClient {
    private static final String SERVICE_TOKEN = "X-CityCopilot-Service-Token";
    private static final String USER_ID = "X-CityCopilot-User-Id";

    private final RestClient client;
    private final String token;

    public CommercePortClient(
            @Value("${citycopilot.commerce.base-url:http://127.0.0.1:8083}") String baseUrl,
            @Value("${citycopilot.commerce.service-token:}") String token
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(3));
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        this.token = token;
    }

    public Result seckill(Long voucherId, String idempotencyKey) {
        try {
            Map<String, Object> response = client.post()
                    .uri("/commerce/v1/seckill/{voucherId}", voucherId)
                    .headers(headers -> headers(headers, userId()))
                    .headers(headers -> {
                        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                            headers.set("Idempotency-Key", idempotencyKey);
                        }
                    })
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            Object orderId = response == null ? null : response.get("orderId");
            return Result.ok(orderId == null ? null : String.valueOf(orderId));
        } catch (RestClientResponseException error) {
            return failure(error);
        } catch (RestClientException error) {
            return unavailable();
        }
    }

    public Result myOrders(Integer page) {
        try {
            List<Map<String, Object>> response = client.get()
                    .uri(builder -> builder.path("/commerce/v1/orders")
                            .queryParam("page", page == null ? 1 : page)
                            .build())
                    .headers(headers -> headers(headers, userId()))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return Result.ok(response == null ? List.of() : response.stream().map(this::stringifyOrderId).toList());
        } catch (RestClientResponseException error) {
            return failure(error);
        } catch (RestClientException error) {
            return unavailable();
        }
    }

    public Result order(Long orderId, boolean admin) {
        try {
            Map<String, Object> response = client.get()
                    .uri(builder -> builder.path("/commerce/v1/orders/{id}")
                            .queryParam("admin", admin)
                            .build(orderId))
                    .headers(headers -> headers(headers, userId()))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return Result.ok(stringifyOrderId(response));
        } catch (RestClientResponseException error) {
            return failure(error);
        } catch (RestClientException error) {
            return unavailable();
        }
    }

    public Result transition(Long orderId, String action, Integer payType, boolean admin) {
        try {
            Map<String, Object> response = client.post()
                    .uri(builder -> builder.path("/commerce/v1/orders/{id}/actions")
                            .queryParam("admin", admin)
                            .build(orderId))
                    .headers(headers -> headers(headers, userId()))
                    .body(Map.of(
                            "action", action,
                            "payType", payType == null ? 1 : payType
                    ))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return Result.ok(stringifyOrderId(response));
        } catch (RestClientResponseException error) {
            return failure(error);
        } catch (RestClientException error) {
            return unavailable();
        }
    }

    public Result vouchers(Long shopId, boolean admin, String keyword, Integer status) {
        try {
            List<Map<String, Object>> response = client.get()
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
            return failure(error);
        } catch (RestClientException error) {
            return unavailable();
        }
    }

    public Result createVoucher(Object voucher) {
        try {
            Map<String, Object> response = client.post()
                    .uri("/commerce/v1/vouchers")
                    .headers(headers -> {
                        headers(headers, userId());
                        headers.set("X-CityCopilot-Admin", String.valueOf(admin()));
                    })
                    .body(voucher)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return Result.ok(response == null ? null : response.get("id"));
        } catch (RestClientResponseException error) {
            return failure(error);
        } catch (RestClientException error) {
            return unavailable();
        }
    }

    public Result updateVoucher(Long voucherId, Object voucher) {
        try {
            Map<String, Object> response = client.post()
                    .uri("/commerce/v1/vouchers/{voucherId}", voucherId)
                    .headers(headers -> {
                        headers(headers, userId());
                        headers.set("X-CityCopilot-Admin", String.valueOf(admin()));
                    })
                    .body(voucher)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return Result.ok(response);
        } catch (RestClientResponseException error) {
            return failure(error);
        } catch (RestClientException error) {
            return unavailable();
        }
    }

    public Map<String, Object> consistency(Long voucherId) {
        return client.get()
                .uri("/commerce/v1/consistency/{voucherId}", voucherId)
                .headers(headers -> headers(headers, userId()))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    public Map<String, Object> repairConsistency(Long voucherId, String evidenceHash) {
        return client.post()
                .uri("/commerce/v1/consistency/{voucherId}/repair", voucherId)
                .headers(headers -> headers(headers, userId()))
                .body(Map.of("expectedEvidenceHash", evidenceHash))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    public Map<String, Object> verifyConsistency(Long voucherId) {
        return client.get()
                .uri("/commerce/v1/consistency/{voucherId}/verify", voucherId)
                .headers(headers -> headers(headers, userId()))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    public Result operationsSummary(Instant fromInclusive, Instant toExclusive) {
        try {
            Map<String, Object> response = client.get()
                    .uri(builder -> builder.path("/commerce/v1/ops/summary")
                            .queryParam("from", fromInclusive)
                            .queryParam("to", toExclusive)
                            .build())
                    .headers(headers -> headers(headers, userId()))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return Result.ok(response == null ? Map.of() : response);
        } catch (RestClientResponseException error) {
            return failure(error);
        } catch (RestClientException error) {
            return unavailable();
        }
    }

    public List<Map<String, Object>> deadLetters(Integer limit) {
        try {
            List<Map<String, Object>> response = client.get()
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

    public Map<String, Object> replayDeadLetter(String sourceId) {
        try {
            return client.post()
                    .uri("/commerce/v1/ops/dead-letters/{sourceId}/replay", sourceId)
                    .headers(this::opsHeaders)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (RestClientException error) {
            throw new IllegalStateException("Commerce dead-letter replay unavailable", error);
        }
    }

    private void headers(HttpHeaders headers, long userId) {
        headers.set(SERVICE_TOKEN, token);
        headers.set(USER_ID, String.valueOf(userId));
    }

    private void opsHeaders(HttpHeaders headers) {
        headers(headers, userId());
        headers.set("X-CityCopilot-Ops-Authorized", String.valueOf(opsAuthorized()));
        headers.set("X-CityCopilot-Actor", actor());
    }

    private Map<String, Object> stringifyOrderId(Map<String, Object> response) {
        if (response == null) {
            return null;
        }
        Map<String, Object> normalized = new LinkedHashMap<>(response);
        Object id = normalized.get("id");
        if (id != null) {
            normalized.put("id", String.valueOf(id));
        }
        Object orderId = normalized.get("orderId");
        if (orderId != null) {
            normalized.put("orderId", String.valueOf(orderId));
        }
        return normalized;
    }

    private long userId() {
        UserDTO user = UserHolder.getUser();
        return user == null || user.getId() == null ? -1L : user.getId();
    }

    private boolean admin() {
        UserDTO user = UserHolder.getUser();
        return user != null && "admin".equalsIgnoreCase(user.getRole());
    }

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

    private Result failure(RestClientResponseException error) {
        try {
            Map<String, Object> body = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(error.getResponseBodyAsByteArray(), Map.class);
            return Result.fail(
                    String.valueOf(body.getOrDefault("code", "COMMERCE_UNAVAILABLE")),
                    String.valueOf(body.getOrDefault("message", "Commerce request failed"))
            );
        } catch (Exception ignored) {
            return Result.fail("COMMERCE_UNAVAILABLE", "Commerce service unavailable");
        }
    }

    private Result unavailable() {
        return Result.fail("COMMERCE_UNAVAILABLE", "Commerce service unavailable");
    }
}
