package com.citycopilot.core.booking;

import com.citycopilot.contract.booking.BookingContracts.*;
import com.citycopilot.contract.booking.BookingPlanContracts.BatchRequest;
import com.citycopilot.contract.booking.BookingPlanContracts.BatchResponse;
import com.citycopilot.contract.booking.PricingContracts.*;
import com.citycopilot.core.dto.UserDTO;
import com.citycopilot.core.config.CrossServiceHttpConfig.CommerceHttpFactories;
import com.citycopilot.core.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.List;

/** Booking HTTP boundary: Commerce owns facts; caller identity always comes from Core's session. */
@Component
public class BookingPortClient {
    private final RestClient client;
    private final String serviceToken;

    @Autowired
    public BookingPortClient(@Value("${citycopilot.commerce.base-url:http://127.0.0.1:8083}") String baseUrl,
                             @Value("${citycopilot.commerce.service-token:}") String serviceToken,
                             CommerceHttpFactories factories) {
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factories.booking()).build();
        this.serviceToken = serviceToken;
    }

    BookingPortClient(RestClient client, String serviceToken) {
        this.client = client;
        this.serviceToken = serviceToken;
    }

    public Rules rules() { return get("/booking-rules", new ParameterizedTypeReference<>() {}); }
    public BatchResponse options(BatchRequest request) {
        return post("/booking-options/query", request, null, new ParameterizedTypeReference<>() {});
    }
    public List<ResourceView> resources(String shopId) {
        return get("/shops/{shopId}/booking-resources", new ParameterizedTypeReference<>() {}, shopId);
    }
    public ResourceView resource(String resourceId) {
        return get("/booking-resources/{id}", new ParameterizedTypeReference<>() {}, resourceId);
    }
    public SlotView slot(String slotId) {
        return get("/booking-slots/{id}", new ParameterizedTypeReference<>() {}, slotId);
    }
    public List<SlotView> slots(String resourceId, Instant from, Instant to) {
        return get("/booking-resources/{id}/slots?dateFrom={from}&dateTo={to}",
                new ParameterizedTypeReference<>() {}, resourceId, from.toString(), to.toString());
    }
    public List<BookingView> bookings(int page) {
        return get("/bookings?page={page}", new ParameterizedTypeReference<>() {}, page);
    }
    public BookingView booking(String id) {
        return get("/bookings/{id}", new ParameterizedTypeReference<>() {}, id);
    }
    public MutationResult hold(HoldRequest request, String key) {
        return post("/bookings/holds", request, key, new ParameterizedTypeReference<>() {});
    }
    public MutationResult confirm(String id, ActionRequest request, String key) {
        return post("/bookings/{id}/confirm", request, key, new ParameterizedTypeReference<>() {}, id);
    }
    public MutationResult cancel(String id, ActionRequest request, String key) {
        return post("/bookings/{id}/cancel", request, key, new ParameterizedTypeReference<>() {}, id);
    }
    public MutationResult reschedule(String id, RescheduleRequest request, String key) {
        return post("/bookings/{id}/reschedule", request, key, new ParameterizedTypeReference<>() {}, id);
    }
    public CommandView command(String key) {
        return get("/booking-commands/{key}", new ParameterizedTypeReference<>() {}, key);
    }
    public List<NoticeView> notices() {
        return get("/booking-notices", new ParameterizedTypeReference<>() {});
    }
    public NoticeView readNotice(String id) {
        return post("/booking-notices/{id}/read", null, null, new ParameterizedTypeReference<>() {}, id);
    }
    public ResourceView createResource(String shopId, ResourceRequest request) {
        return post("/admin/shops/{shopId}/booking-resources", request, null, new ParameterizedTypeReference<>() {}, shopId);
    }
    public List<ResourceView> adminResources(String shopId) {
        return get("/admin/shops/{shopId}/booking-resources", new ParameterizedTypeReference<>() {}, shopId);
    }
    public ResourceView updateResource(String id, ResourceRequest request) {
        return call(HttpMethod.PUT, "/admin/booking-resources/{id}", request, null, new ParameterizedTypeReference<>() {}, id);
    }
    public SlotView saveSlot(String id, SlotRequest request) {
        return post("/admin/booking-resources/{id}/slots", request, null, new ParameterizedTypeReference<>() {}, id);
    }
    public List<BookingView> shopBookings(String shopId, Instant from, Instant to) {
        return get("/admin/shops/{id}/bookings?from={from}&to={to}", new ParameterizedTypeReference<>() {}, shopId, from.toString(), to.toString());
    }
    public BookingView adminBooking(String id) {
        return get("/admin/bookings/{id}", new ParameterizedTypeReference<>() {}, id);
    }
    public MutationResult checkIn(String id, ActionRequest request, String key) {
        return post("/admin/bookings/{id}/check-in", request, key, new ParameterizedTypeReference<>() {}, id);
    }
    public MutationResult merchantCancel(String id, ActionRequest request, String key) {
        return post("/admin/bookings/{id}/cancel", request, key, new ParameterizedTypeReference<>() {}, id);
    }

    public List<OfferView> offers(String resourceId) {
        return get("/booking-resources/{id}/offers", new ParameterizedTypeReference<>() {}, resourceId);
    }
    public OfferView saveOffer(String resourceId, OfferRequest request) {
        return post("/admin/booking-resources/{id}/offers", request, null, new ParameterizedTypeReference<>() {}, resourceId);
    }
    public QuoteView quote(QuoteRequest request) {
        return post("/booking-quotes", request, null, new ParameterizedTypeReference<>() {});
    }
    public QuoteView quote(String id) {
        return get("/booking-quotes/{id}", new ParameterizedTypeReference<>() {}, id);
    }
    public List<OfferView> adminOffers(String id) {
        return get("/admin/booking-resources/{id}/offers", new ParameterizedTypeReference<>() {}, id);
    }
    public List<RuleView> couponRules(String shopId, boolean admin) {
        return get((admin ? "/admin" : "") + "/shops/{id}/booking-coupon-rules", new ParameterizedTypeReference<>() {}, shopId);
    }
    public RuleView publishRule(String shopId, RuleRequest request) {
        return post("/admin/shops/{id}/booking-coupon-rules", request, null, new ParameterizedTypeReference<>() {}, shopId);
    }
    public RuleView issuance(String id, IssuanceRequest request) {
        return post("/admin/booking-coupon-rules/{id}/issuance", request, null, new ParameterizedTypeReference<>() {}, id);
    }
    public CouponView revoke(String id, RevokeRequest request) {
        return post("/admin/booking-coupons/{id}/revoke", request, null, new ParameterizedTypeReference<>() {}, id);
    }
    public List<CouponView> coupons(String shopId) {
        return shopId == null ? get("/booking-coupons", new ParameterizedTypeReference<>() {})
                : get("/booking-coupons?shopId={id}", new ParameterizedTypeReference<>() {}, shopId);
    }
    public CouponView claim(ClaimRequest request, String key) {
        return post("/booking-coupons/claims", request, key, new ParameterizedTypeReference<>() {});
    }

    private <T> T get(String path, ParameterizedTypeReference<T> type, Object... variables) {
        return call(HttpMethod.GET, path, null, null, type, variables);
    }
    private <T> T post(String path, Object body, String key, ParameterizedTypeReference<T> type, Object... variables) {
        return call(HttpMethod.POST, path, body, key, type, variables);
    }
    private <T> T call(HttpMethod method, String path, Object body, String key,
                       ParameterizedTypeReference<T> type, Object... variables) {
        UserDTO user = UserHolder.getUser();
        var request = client.method(method).uri("/commerce/v1" + path, variables).headers(headers -> {
            headers.set("X-CityCopilot-Service-Token", serviceToken);
            headers.set("X-CityCopilot-User-Id", user == null || user.getId() == null ? "-1" : user.getId().toString());
            headers.set("X-CityCopilot-Platform-Admin", String.valueOf(user != null && "admin".equals(user.getRole())));
            if (key != null) headers.set("Idempotency-Key", key);
        });
        if (body != null) request.body(body);
        try {
            return request.retrieve().body(type);
        } catch (RestClientResponseException error) {
            throw error;
        } catch (RestClientException error) {
            // A write timeout cannot tell whether Commerce committed; the caller must retain its operation key.
            throw new Unavailable(key != null, error);
        }
    }

    public static final class Unavailable extends RuntimeException {
        final boolean outcomeUnknown;
        Unavailable(boolean outcomeUnknown, Throwable cause) {
            super("Commerce booking service unavailable", cause);
            this.outcomeUnknown = outcomeUnknown;
        }
    }
}
