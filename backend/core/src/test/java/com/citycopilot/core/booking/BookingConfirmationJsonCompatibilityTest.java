package com.citycopilot.core.booking;

import com.citycopilot.core.dto.UserDTO;
import com.citycopilot.core.utils.UserHolder;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingConfirmationJsonCompatibilityTest {
    @Test
    void persistedConfirmationKeepsItsHashOperationKeyAndTamperCheck() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withPropertyValues("spring.jackson.default-property-inclusion=non_null",
                        "spring.jackson.use-jackson2-defaults=true")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    ObjectMapper json = context.getBean(ObjectMapper.class);
                    String fixture;
                    try (var input = getClass().getResourceAsStream("/booking/confirmation-jackson2.json")) {
                        assertNotNull(input);
                        fixture = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    var stored = json.readTree(fixture);
                    String operationId = stored.path("operationId").asText();
                    String payload = stored.path("payload").asText();
                    var bound = json.readValue(payload, BookingConfirmationService.BoundAction.class);
                    assertEquals(payload, json.writeValueAsString(bound));

                    StringRedisTemplate redis = mock(StringRedisTemplate.class);
                    HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
                    when(redis.opsForHash()).thenReturn(hashes);
                    String key = "booking:confirmation:" + operationId;
                    when(hashes.entries(key)).thenReturn(Map.of("payload", payload, "state", "PENDING"));
                    var commerce = mock(BookingPortClient.class);
                    var service = new BookingConfirmationService(commerce, redis, json);
                    var user = new UserDTO();
                    user.setId(Long.valueOf(bound.ownerId()));
                    UserHolder.saveUser(user);
                    try {
                        var card = service.status(operationId);
                        assertEquals(operationId, card.operationId());
                        assertEquals(bound.requestHash(), card.requestHash());
                        assertEquals("PENDING", card.state());
                        assertEquals(19900L, card.quote().totalCents());
                        ObjectNode tampered = (ObjectNode) json.readTree(payload);
                        ((ObjectNode) tampered.get("quote")).put("totalCents", 1);
                        when(hashes.entries(key)).thenReturn(Map.of(
                                "payload", json.writeValueAsString(tampered), "state", "PENDING"));
                        assertEquals(409, assertThrows(ResponseStatusException.class,
                                () -> service.status(operationId)).getStatusCode().value());
                        verifyNoInteractions(commerce);
                    } finally {
                        UserHolder.removeUser();
                    }
                });
    }
}
