package com.citycopilot.commerce;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class PlaceAuthorityClient {
    private final RestClient client;
    private final String token;

    public PlaceAuthorityClient(
            @Value("${citycopilot.commerce.place-base-url}") String baseUrl,
            @Value("${citycopilot.commerce.service-token}") String token
    ) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
        this.token = token;
    }

    public Map<String, Object> requireWriteAuthority(long shopId, long actorId, boolean admin) {
        try {
            Map<String, Object> result = client.get()
                    .uri("/internal/v1/place/shops/{shopId}/authority", shopId)
                    .header("X-CityCopilot-Service-Token", token)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
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
            throw new CommerceApiException(
                    "COMMERCE_PLACE_UNAVAILABLE",
                    "Place authority is unavailable; voucher write failed closed",
                    503
            );
        }
    }
}
