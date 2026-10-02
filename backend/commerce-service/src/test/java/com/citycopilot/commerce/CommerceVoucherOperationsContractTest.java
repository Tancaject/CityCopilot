package com.citycopilot.commerce;

import com.citycopilot.contract.commerce.CommerceContracts.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommerceVoucherOperationsContractTest {
    private static final long ID = 9007199254740993L;
    private static final Timestamp TIME = Timestamp.from(Instant.parse("2026-09-25T00:00:00.123Z"));

    @Test
    @SuppressWarnings("unchecked")
    void voucherQueriesKeepNumericIdsCentsAndNullableSeckillDates() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(ID);
        when(row.getLong("shop_id")).thenReturn(7L);
        when(row.getString("title")).thenReturn("双人券");
        when(row.getInt("pay_value")).thenReturn(9900);
        when(row.getInt("actual_value")).thenReturn(12000);
        when(row.getInt("status")).thenReturn(1);
        when(row.getInt("version")).thenReturn(2);
        when(row.getTimestamp("create_time")).thenReturn(TIME);
        when(row.getTimestamp("update_time")).thenReturn(TIME);
        doAnswer(call -> List.of(((RowMapper<?>) call.getArgument(1)).mapRow(row, 0)))
                .when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        CommerceVoucherService service = vouchers(jdbc);
        VoucherView result = service.list(7).get(0);
        assertThat(service.adminList(7L, "双人", 1)).containsExactly(result);
        verify(jdbc).query(contains("AND v.status=1"), any(RowMapper.class), eq(7L));
        verify(jdbc).query(contains("LIMIT 100"), any(RowMapper.class), eq(7L), eq(7L), eq(1), eq(1), eq("双人"), eq("双人"));

        jsonContext().run(context -> {
            ObjectMapper json = context.getBean(ObjectMapper.class);
            Map<String, Object> legacy = new LinkedHashMap<>(Map.of(
                    "id", ID, "shopId", 7L, "title", "双人券", "payValue", 9900, "actualValue", 12000,
                    "type", 0, "status", 1, "version", 2, "createTime", TIME, "updateTime", TIME));
            for (String name : List.of("subTitle", "rules", "stock", "beginTime", "endTime")) legacy.put(name, null);
            assertThat(json.<JsonNode>valueToTree(result)).isEqualTo(json.valueToTree(legacy));
            assertThat(json.<JsonNode>valueToTree(new VoucherCreated(ID))).isEqualTo(json.valueToTree(Map.of("id", ID)));
            when(row.getObject("stock", Integer.class)).thenReturn(5);
            when(row.getTimestamp("begin_time")).thenReturn(TIME);
            when(row.getTimestamp("end_time")).thenReturn(TIME);
            VoucherView seckill = service.list(7).get(0);
            assertThat(seckill.stock()).isEqualTo(5);
            assertThat(json.readValue(json.writeValueAsBytes(seckill), VoucherView.class).beginTime()).hasTime(TIME.getTime());
        });
    }

    @Test
    void sparseUpdatesKeepAbsentAndExplicitNullSemantics() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString(), eq(ID))).thenReturn(Map.of(
                "id", ID, "shop_id", 7L, "title", "双人券", "type", 0,
                "status", 1, "pay_value", 9900, "actual_value", 12000));
        CommerceVoucherService service = vouchers(jdbc);
        service.update(ID, Map.of("status", 2), 7, true);
        verify(jdbc).update(contains("SET title=COALESCE"), isNull(), isNull(), isNull(),
                isNull(), isNull(), eq(2), eq(ID));
        Map<String, Object> explicitNull = new LinkedHashMap<>();
        explicitNull.put("payValue", null);
        assertThatThrownBy(() -> service.update(ID, explicitNull, 7, true))
                .isInstanceOf(CommerceApiException.class).hasMessage("payValue is required");
        explicitNull.clear();
        explicitNull.put("stock", null);
        assertThatThrownBy(() -> service.update(ID, explicitNull, 7, true))
                .isInstanceOf(CommerceApiException.class).hasMessage("Seckill fields cannot be set on a normal voucher");
        verify(jdbc, times(1)).update(anyString(), any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void operationsRowsRetainLongCountsDateEncodingAndExplicitNulls() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = mock(ResultSet.class);
        when(row.getLong(anyString())).thenReturn(3000000000L);
        when(row.getString("source_id")).thenReturn("1-0");
        when(row.getString("error_message")).thenReturn("provider unavailable");
        when(row.getInt("retry_count")).thenReturn(3);
        when(row.getTimestamp("created_at")).thenReturn(TIME);
        doAnswer(call -> ((RowMapper<?>) call.getArgument(1)).mapRow(row, 0))
                .when(jdbc).queryForObject(anyString(), any(RowMapper.class), any(Object[].class));
        doAnswer(call -> List.of(((RowMapper<?>) call.getArgument(1)).mapRow(row, 0)))
                .when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        CommerceOperationsService service = operations(jdbc, mock(CommerceOrderAdmissionService.class));
        OperationsSummary summary = service.summary(TIME.toInstant(), TIME.toInstant().plusSeconds(60));
        DeadLetterView dead = service.deadLetters(200).get(0);
        verify(jdbc).query(contains("LIMIT ?"), any(RowMapper.class), eq(100));
        assertThat(summary.orders()).isEqualTo(3000000000L);
        jsonContext().run(context -> {
            ObjectMapper json = context.getBean(ObjectMapper.class);
            assertThat(json.<JsonNode>valueToTree(summary)).isEqualTo(json.valueToTree(Map.of(
                    "orders", 3000000000L, "todayOrders", 3000000000L, "unpaidOrders", 3000000000L,
                    "cancelledOrders", 3000000000L, "vouchers", 3000000000L,
                    "seckillVouchers", 3000000000L, "activeVouchers", 3000000000L)));
            Map<String, Object> legacy = new LinkedHashMap<>(Map.of("recordId", "1-0", "error", "provider unavailable",
                    "retryCount", 3, "createdAt", TIME));
            legacy.put("replayedAt", null);
            legacy.put("recoveryPhase", null);
            assertThat(json.<JsonNode>valueToTree(dead)).isEqualTo(json.valueToTree(legacy));
        });
    }

    @Test
    void replayWireKeepsThreeSparseBranchesWithoutChangingOtherNulls() {
        jsonContext().run(context -> {
            ObjectMapper json = context.getBean(ObjectMapper.class);
            assertThat(json.<JsonNode>valueToTree(new DeadLetterReplayResult("already_replayed", "1-0", null, null, null, null, null)))
                    .isEqualTo(json.readTree("{\"status\":\"already_replayed\",\"deadLetterRecordId\":\"1-0\"}"));
            assertThat(json.<JsonNode>valueToTree(new DeadLetterReplayResult("replayed", "1-0", Long.toString(ID),
                    "processing", "COMMERCE_ORDER_ACCEPTED", "key", null))).isEqualTo(json.readTree("""
                    {"status":"replayed","deadLetterRecordId":"1-0","orderId":"9007199254740993",
                     "state":"processing","code":"COMMERCE_ORDER_ACCEPTED","idempotencyKey":"key"}
                    """));
            assertThat(json.<JsonNode>valueToTree(new DeadLetterReplayResult("replayed", "1-0", Long.toString(ID),
                    "completed", "IDEMPOTENT_REPLAY", null, 2))).isEqualTo(json.readTree("""
                    {"status":"replayed","deadLetterRecordId":"1-0","orderId":"9007199254740993",
                     "state":"completed","code":"IDEMPOTENT_REPLAY","version":2}
                    """));
            assertThat(json.<JsonNode>valueToTree(new AdmissionRepairRequeued("requeued", Long.toString(ID))))
                    .isEqualTo(json.readTree("{\"status\":\"requeued\",\"orderId\":\"9007199254740993\"}"));
            assertThat(json.<JsonNode>valueToTree(new DeadLetterView("1-0", null, 3, TIME, null, null)).has("replayedAt")).isTrue();
        });
    }

    @Test
    void completedAndRepeatedReplayKeepTheirExistingResultAndAuditBoundaries() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CommerceOrderAdmissionService admissions = mock(CommerceOrderAdmissionService.class);
        CommerceOperationsService service = operations(jdbc, admissions);
        when(jdbc.queryForMap(anyString(), eq("1-0"))).thenReturn(Map.of("payload_json",
                "{\"orderId\":\"9007199254740993\",\"voucherId\":10,\"userId\":7,\"idempotencyKey\":\"key\"}"));
        when(admissions.replayAdmission(ID, 10, 7, "key")).thenReturn(Map.of(
                "orderId", Long.toString(ID), "status", 2, "version", 3, "state", "completed", "code", "IDEMPOTENT_REPLAY"));
        when(jdbc.update(contains("SET replayed_at"), eq("1-0"))).thenReturn(1);
        assertThat(service.replayDeadLetter("1-0", "operator")).isEqualTo(new DeadLetterReplayResult(
                "replayed", "1-0", Long.toString(ID), "completed", "IDEMPOTENT_REPLAY", null, 3));
        verify(jdbc).update(contains("INSERT INTO commerce_outbox_event"), any(), any(), any(), any(), any(), any());
        clearInvocations(jdbc, admissions);
        when(jdbc.queryForMap(anyString(), eq("1-0"))).thenReturn(Map.of("replayed_at", TIME));
        assertThat(service.replayDeadLetter("1-0", "operator")).isEqualTo(new DeadLetterReplayResult(
                "already_replayed", "1-0", null, null, null, null, null));
        verifyNoInteractions(admissions);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    private CommerceVoucherService vouchers(JdbcTemplate jdbc) {
        return new CommerceVoucherService(jdbc, mock(PlaceAuthorityClient.class), mock(CommerceOrderIdGenerator.class),
                mock(CommerceRedisInventoryBootstrap.class), mock(CommerceRedisCampaignMetadata.class));
    }

    private CommerceOperationsService operations(JdbcTemplate jdbc, CommerceOrderAdmissionService admissions) {
        return new CommerceOperationsService(jdbc, admissions, JsonMapper.builderWithJackson2Defaults().build(),
                mock(CommerceRoutingGuard.class), mock(CommerceAdmissionDurabilityService.class));
    }

    private ApplicationContextRunner jsonContext() {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withUserConfiguration(CommerceContractJsonConfig.class)
                .withPropertyValues("spring.jackson.use-jackson2-defaults=true");
    }
}
