package com.citycopilot.core.booking;

import tools.jackson.databind.json.JsonMapper;

import com.citycopilot.contract.booking.BookingConfirmationContracts.*;
import com.citycopilot.contract.booking.BookingContracts.*;
import com.citycopilot.contract.booking.PricingContracts.QuoteView;
import com.citycopilot.core.dto.UserDTO;
import com.citycopilot.core.utils.UserHolder;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Uses real Redis Lua transitions; only this test's randomly generated confirmation keys are removed. */
@EnabledIfEnvironmentVariable(named = "CITYCOPILOT_BOOKING_TEST_REDIS_PORT", matches = "[0-9]+")
class BookingConfirmationServiceIT {
    private static LettuceConnectionFactory connection;
    private static StringRedisTemplate redis;
    private final ObjectMapper json = JsonMapper.builderWithJackson2Defaults().findAndAddModules().build();
    private final List<String> keys = new ArrayList<>();
    private BookingPortClient commerce;
    private BookingConfirmationService confirmations;
    private QuoteView quote;
    private Instant now;

    @BeforeAll static void connect() {
        var config = new RedisStandaloneConfiguration(System.getenv().getOrDefault("CITYCOPILOT_BOOKING_TEST_REDIS_HOST", "127.0.0.1"),
                Integer.parseInt(System.getenv("CITYCOPILOT_BOOKING_TEST_REDIS_PORT")));
        connection = new LettuceConnectionFactory(config); connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection); redis.afterPropertiesSet();
    }
    @AfterAll static void close() { if (connection != null) connection.destroy(); }
    @BeforeEach void setup() {
        owner(701);
        now = Instant.now();
        commerce = mock(BookingPortClient.class);
        confirmations = new BookingConfirmationService(commerce, redis, json);
        when(commerce.rules()).thenReturn(json.convertValue(Map.of("serverTime", now.toString()), Rules.class));
        when(commerce.resource("11")).thenReturn(new ResourceView("11", "1", "COURT", "Court A", "Asia/Shanghai", 4, "OPEN", 1));
        when(commerce.slot("22")).thenReturn(new SlotView("22", "11", now.plusSeconds(3600), now.plusSeconds(7200), 1, 0, 1, "OPEN", 1));
        quote = json.convertValue(Map.ofEntries(Map.entry("quoteId", UUID.randomUUID().toString()), Map.entry("userId", "701"),
                Map.entry("purpose", "CREATE"), Map.entry("resourceId", "11"), Map.entry("slotId", "22"),
                Map.entry("partySize", 2), Map.entry("pricingMode", "FIXED"), Map.entry("totalCents", 9900),
                Map.entry("expiresAt", now.plusSeconds(300).toString()), Map.entry("discountLines", List.of())), QuoteView.class);
        when(commerce.quote(quote.quoteId())).thenReturn(quote);
        when(commerce.command(anyString())).thenThrow(new RestClientResponseException("missing", 404, "", null, new byte[0], null));
    }
    @AfterEach void cleanup() { UserHolder.removeUser(); if (!keys.isEmpty()) redis.delete(keys); }
    private ConfirmationView propose() {
        var card = confirmations.propose(new ProposalRequest("HOLD", quote.quoteId(), null, null, null));
        keys.add("booking:confirmation:" + card.operationId()); return card;
    }
    private void owner(long id) { var user = new UserDTO(); user.setId(id); UserHolder.saveUser(user); }

    @Test void modelToolsOnlyProposeAndWrongOwnerCannotReadOrApprove() {
        var tools = new BookingTools(commerce, confirmations);
        var card = tools.hold(quote.quoteId()); keys.add("booking:confirmation:" + card.operationId());
        assertEquals("PENDING", card.state());
        verify(commerce, never()).hold(any(), anyString());
        owner(702);
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> confirmations.status(card.operationId())).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> confirmations.approve(card.operationId(), card.requestHash())).getStatusCode().value());
        verify(commerce, never()).hold(any(), anyString());
    }
    @Test void tamperedPriceAndExpiredAtomicApprovalAreRejected() throws Exception {
        var card = propose(); String key = keys.get(0);
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> confirmations.approve(card.operationId(), "different-review")).getStatusCode().value());
        String payload = (String) redis.opsForHash().get(key, "payload");
        var tampered = json.readTree(payload);
        ((tools.jackson.databind.node.ObjectNode) tampered.get("quote")).put("totalCents", 1);
        redis.opsForHash().put(key, "payload", json.writeValueAsString(tampered));
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> confirmations.approve(card.operationId(), card.requestHash())).getStatusCode().value());
        redis.opsForHash().put(key, "payload", payload);
        redis.opsForHash().put(key, "expiresAt", String.valueOf(now.minusSeconds(1).toEpochMilli()));
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> confirmations.approve(card.operationId(), card.requestHash())).getStatusCode().value());
        verify(commerce, never()).hold(any(), anyString());
    }
    @Test void lostReplySurvivesASecondCoreInstanceAndKeepsOriginalCommerceKey() {
        var card = propose();
        var success = new MutationResult("OK", 200, "held", null);
        when(commerce.hold(any(), eq(card.operationId())))
                .thenThrow(new BookingPortClient.Unavailable(true, new RestClientException("lost response")))
                .thenReturn(success);
        assertEquals("APPROVED", confirmations.approve(card.operationId(), card.requestHash()).state());
        assertTrue(redis.getExpire(keys.get(0)) >= 86400);
        var secondCore = new BookingConfirmationService(commerce, redis, json);
        assertEquals("APPROVED", secondCore.status(card.operationId()).state());
        assertEquals("SUCCEEDED", secondCore.approve(card.operationId(), card.requestHash()).state());
        assertEquals("SUCCEEDED", confirmations.approve(card.operationId(), card.requestHash()).state());
        verify(commerce, times(2)).hold(any(), eq(card.operationId()));
        assertEquals(card.operationId(), confirmations.propose(new ProposalRequest("HOLD", quote.quoteId(), null, null, null)).operationId());
    }
    @Test void finalConfirmationUsesHeldDeadlineAndFrozenPriceWithoutFetchingExpiredQuote() {
        var held = json.convertValue(Map.ofEntries(Map.entry("id", UUID.randomUUID().toString()), Map.entry("userId", "701"),
                Map.entry("resourceId", "11"), Map.entry("slotId", "22"), Map.entry("partySize", 2), Map.entry("status", "HELD"),
                Map.entry("version", 1), Map.entry("allowedActions", List.of("CONFIRM", "CANCEL")),
                Map.entry("holdExpiresAt", now.plusSeconds(120).toString()),
                Map.entry("priceSnapshot", Map.of("totalCents", 9900, "expiresAt", now.minusSeconds(300).toString()))), BookingView.class);
        when(commerce.booking(held.id())).thenReturn(held);
        var card = confirmations.propose(new ProposalRequest("CONFIRM", null, held.id(), 1L, null));
        keys.add("booking:confirmation:" + card.operationId());
        assertEquals(held.holdExpiresAt(), card.expiresAt());
        assertEquals(9900, card.previous().priceSnapshot().get("totalCents"));
        verify(commerce, never()).quote(anyString());
        confirmations.decline(card.operationId());
        assertThrows(ResponseStatusException.class, () -> confirmations.approve(card.operationId(), card.requestHash()));
        verify(commerce, never()).confirm(anyString(), any(), anyString());
    }
}
