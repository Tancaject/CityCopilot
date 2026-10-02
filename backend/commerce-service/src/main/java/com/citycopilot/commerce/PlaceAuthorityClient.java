package com.citycopilot.commerce;

import com.citycopilot.http.DeadlineHttpRequestFactory;
import com.citycopilot.http.RequestDeadline;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.time.Duration;

/**
 * 调用 Place 权威服务校验店铺存在性和写入权限。
 *
 * <p>Voucher 写操作依赖该结果；权威服务不可用时按失败关闭处理，避免越权或写入孤儿店铺数据。</p>
 */
@Component
public class PlaceAuthorityClient {
    /** 指向 Place 内部接口的同步 HTTP 客户端。 */
    private final RestClient client;
    /** 服务间调用使用的共享令牌。 */
    private final String token;

    public PlaceAuthorityClient(
            @Value("${citycopilot.commerce.place-base-url}") String baseUrl,
            @Value("${citycopilot.commerce.service-token}") String token,
            @Value("${citycopilot.http.place.connect-timeout:2s}") Duration connectTimeout,
            @Value("${citycopilot.http.place.response-timeout:3s}") Duration responseTimeout,
            @Value("${citycopilot.http.place.max-concurrent:32}") int maxConcurrent
    ) {
        this.client = RestClient.builder().baseUrl(baseUrl)
                .requestFactory(new DeadlineHttpRequestFactory(connectTimeout, responseTimeout,
                        RequestDeadline::current, true, maxConcurrent)).build();
        this.token = token;
    }

    /**
     * 校验操作者是否有权修改指定店铺，并返回 Place 的权威店铺信息。
     *
     * @param shopId 店铺 ID
     * @param actorId 发起写操作的用户 ID
     * @param admin 是否以管理员身份操作
     * @return Place 服务返回的店铺权威信息
     * @throws CommerceApiException 店铺不存在、无权限或 Place 服务不可用时抛出
     */
    public Map<String, Object> requireWriteAuthority(long shopId, long actorId, boolean admin) {
        try {
            Map<String, Object> result = client.get()
                    .uri("/internal/v1/place/shops/{shopId}/authority", shopId)
                    .header("X-CityCopilot-Service-Token", token)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            // 后续 Voucher 写入只信任 Place 返回的存在性和 ownerUserId，不接受请求体自报归属。
            if (result == null || !Boolean.TRUE.equals(result.get("exists"))) {
                throw new CommerceApiException("COMMERCE_SHOP_NOT_FOUND", "Authoritative shop does not exist", 404);
            }
            long owner = ((Number) result.get("ownerUserId")).longValue();
            if (!admin && owner != actorId) {
                throw new CommerceApiException("COMMERCE_SHOP_FORBIDDEN", "Actor does not own the shop", 403);
            }
            return result;
        } catch (CommerceApiException known) {
            throw known;
        } catch (Exception unavailable) {
            // 无法确认权限等同于无权写入，避免依赖故障时绕过权威校验。
            throw new CommerceApiException(
                    "COMMERCE_PLACE_UNAVAILABLE",
                    "Place authority is unavailable; voucher write failed closed",
                    503
            );
        }
    }
}
