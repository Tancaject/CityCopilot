package com.citycopilot.core.booking;

import com.citycopilot.contract.booking.BookingContracts.Rules;
import com.citycopilot.contract.booking.BookingPlanContracts.*;
import com.citycopilot.core.dto.ShopDiscoveryItemDTO;
import com.citycopilot.core.dto.UserDTO;
import com.citycopilot.core.service.discovery.LocalShopDiscoveryProvider;
import com.citycopilot.core.service.discovery.ShopDiscoveryQuery;
import com.citycopilot.core.tool.CoreToolRegistry;
import com.citycopilot.core.utils.UserHolder;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingPlanServiceTest {
    private final LocalShopDiscoveryProvider discovery = mock(LocalShopDiscoveryProvider.class);
    private final BookingPortClient bookings = mock(BookingPortClient.class);
    private final BookingFeedbackService feedback = mock(BookingFeedbackService.class);
    private final BookingPlanService plans = new BookingPlanService(discovery, bookings, feedback);
    private final Instant now = Instant.parse("2026-09-10T00:00:00Z");

    @BeforeEach
    void setup() {
        when(bookings.rules()).thenReturn(new Rules(60, 14, 120, 1, 3, 8, 30, 15, 30, "Asia/Shanghai", now, true));
        when(feedback.features(anyLong(), anyList())).thenReturn(Map.of());
        when(feedback.rules()).thenReturn(new BookingFeedbackService.EffectiveRules(false, 30, 30, 60, 3600000,
                "WITH_BOOKING", "UNTIL_CLEARED", List.of(), "ISSUED_CANDIDATES"));
        var user = new UserDTO();
        user.setId(1L);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void clear() { UserHolder.removeUser(); }

    @Test
    void ambiguousBudgetAndMissingPeopleClarifyWithoutQueryingOrReserving() {
        var request = new PlanRequest("DINING_POOL", LocalDate.parse("2026-09-11"), LocalTime.of(18, 0), LocalTime.of(21, 0),
                null, null, null, null, null, null, 20_000L, null, List.of());
        var result = plans.plan(request);
        assertEquals("NEEDS_INPUT", result.status());
        assertEquals(List.of("partySize", "budgetBasis"), result.clarifications().stream().map(Clarification::field).toList());
        verifyNoInteractions(discovery);
        verify(bookings, never()).options(any());
        verify(bookings, never()).hold(any(), any());
    }

    @Test
    void perPersonBudgetBecomesExactTotalAndDistanceConstraintReachesDiscovery() {
        when(discovery.discover(any())).thenReturn(List.of(
                shop(1, true, "local", 7000d), shop(2, true, "local", 12000d), shop(3, false, "amap", 100d)));
        when(bookings.options(any())).thenReturn(new BatchResponse(List.of(), now, false));
        var request = new PlanRequest("DINING_POOL", LocalDate.parse("2026-09-11"), LocalTime.of(18, 0), LocalTime.of(21, 0),
                "Asia/Shanghai", 4, "聚餐", 120d, 30d, 10_000d, 5000L, "PER_PERSON", List.of());
        plans.plan(request);
        var batch = ArgumentCaptor.forClass(BatchRequest.class);
        verify(bookings).options(batch.capture());
        assertEquals(List.of("1"), batch.getValue().shopIds());
        assertEquals(20_000L, batch.getValue().maxTotalCents());
        assertEquals(Instant.parse("2026-09-11T10:00:00Z"), batch.getValue().startFrom());
        var query = ArgumentCaptor.forClass(ShopDiscoveryQuery.class);
        verify(discovery).discover(query.capture());
        assertEquals(10_000d, query.getValue().getRadiusMeters());
        verify(bookings, never()).hold(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolCatalogDescribesStructuredRequestAndInvalidCoordinatesCannotQuery() {
        try (var context = new GenericApplicationContext()) {
            context.registerBean("plans", BookingPlanService.class, () -> plans);
            context.refresh();
            var registry = new CoreToolRegistry(context);
            registry.afterSingletonsInstantiated();
            var definition = registry.definitions().stream().filter(tool -> tool.name().equals("search_bookable_options")).findFirst().orElseThrow();
            var properties = (Map<String, Object>) definition.parameters().get(0).schema().get("properties");
            assertEquals("date", ((Map<String, Object>) properties.get("date")).get("format"));
            assertEquals("integer", ((Map<String, Object>) properties.get("budgetCents")).get("type"));
        }
        var invalid = new PlanRequest("COURT", LocalDate.parse("2026-09-11"), LocalTime.of(18, 0), LocalTime.of(21, 0),
                null, 2, null, Double.NaN, 30d, null, null, null, null);
        assertThrows(ResponseStatusException.class, () -> plans.plan(invalid));
        verifyNoInteractions(discovery);
    }

    private ShopDiscoveryItemDTO shop(long id, boolean orderable, String source, Double distance) {
        return new ShopDiscoveryItemDTO().setId(id).setName("预约店铺").setOrderable(orderable).setSource(source).setDistance(distance);
    }
}
