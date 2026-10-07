package com.citycopilot.commerce;

import com.citycopilot.contract.commerce.CommerceContracts.OrderSummary;
import com.citycopilot.contract.commerce.CommerceContracts.OrderView;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
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

class CommerceOrderQueryContractTest {
    private static final long ID = 9007199254740993L;
    private static final Timestamp CREATED = Timestamp.from(Instant.parse("2026-09-25T00:00:00.123Z"));

    @Test
    @SuppressWarnings("unchecked")
    void typedRowsRetainOwnershipPaginationAndLegacyTimeEncoding() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = row();
        doAnswer(call -> List.of(((RowMapper<?>) call.getArgument(1)).mapRow(row, 0)))
                .when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        CommerceOrderService service = service(jdbc, null, null);
        OrderView detail = service.order(ID, 7, false, false);
        assertThat(detail.id()).isEqualTo(Long.toString(ID));
        assertThat(detail.payValue()).isEqualTo(9900);
        assertThat(detail.payTime()).isNull();
        verify(jdbc).query(contains("AND user_id=?"), any(RowMapper.class), eq(ID), eq(7L), eq(ID), eq(7L));
        service.order(ID, 7, true, false);
        verify(jdbc).query(argThat((String sql) -> !sql.contains("AND user_id=?")), any(RowMapper.class), eq(ID), eq(ID));
        OrderSummary summary = service.myOrders(7, 2).get(0);
        verify(jdbc).query(contains("WHERE user_id=?"), any(RowMapper.class), eq(7L), eq(10));
        assertThat(summary.id()).isEqualTo(detail.id());

        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withPropertyValues("spring.jackson.use-jackson2-defaults=true")
                .run(context -> {
                    ObjectMapper json = context.getBean(ObjectMapper.class);
                    Map<String, Object> legacy = new LinkedHashMap<>(Map.of(
                            "id", Long.toString(ID), "userId", 7L, "voucherId", 10L, "shopId", 20L,
                            "shopName", "店铺", "voucherTitle", "双人券", "status", 4, "version", 2,
                            "createTime", CREATED, "updateTime", CREATED));
                    legacy.put("voucherSubTitle", null);
                    legacy.put("voucherRules", "预约后使用");
                    legacy.put("payValue", 9900);
                    legacy.put("actualValue", 12000);
                    legacy.put("state", "cancelled");
                    legacy.put("statusText", "已取消");
                    assertThat(json.<tools.jackson.databind.JsonNode>valueToTree(summary)).isEqualTo(json.valueToTree(legacy));
                    legacy.put("payType", null);
                    legacy.put("payTime", null);
                    legacy.put("useTime", null);
                    legacy.put("refundTime", null);
                    legacy.put("inventoryRecovery", null);
                    assertThat(json.<tools.jackson.databind.JsonNode>valueToTree(detail)).isEqualTo(json.valueToTree(legacy));
                    assertThat(json.readValue(json.writeValueAsBytes(detail), OrderView.class).createTime())
                            .hasTime(CREATED.getTime());
                });
        doReturn(List.of()).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        assertThatThrownBy(() -> service.order(ID, 8, false, false)).isInstanceOfSatisfying(
                CommerceApiException.class, error -> assertThat(error.code()).isEqualTo("COMMERCE_ORDER_NOT_FOUND"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void cancellationRetainsPendingInventoryRecoveryInTypedResult() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = row();
        doAnswer(call -> List.of(((RowMapper<?>) call.getArgument(1)).mapRow(row, 0)))
                .when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(call ->
                ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(true);
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("Redis unavailable"));

        OrderView result = service(jdbc, redis, transactions).transition(ID, 7, "cancel", false, false);
        assertThat(result.inventoryRecovery()).isEqualTo("RESTORE_PENDING");
        assertThat(result.id()).isEqualTo(Long.toString(ID));
        verify(jdbc).update(contains("WHERE id=? AND status=? AND user_id=?"), eq(4), eq(ID), eq(1), eq(7L));
        verify(jdbc).update(contains("INSERT INTO commerce_order_recovery"),
                eq("transition:" + ID + ":cancel:2"), eq(ID), eq(10L), eq(7L),
                anyString(), eq("RESTORE_PENDING"), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminCancellationRecordsTheOrderOwnerNotTheOperator() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = row();
        doAnswer(call -> List.of(((RowMapper<?>) call.getArgument(1)).mapRow(row, 0)))
                .when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(call ->
                ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(true);
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("Redis unavailable"));

        OrderView result = service(jdbc, redis, transactions).transition(ID, 99, "cancel", true, false);
        assertThat(result.inventoryRecovery()).isEqualTo("RESTORE_PENDING");
        assertThat(result.id()).isEqualTo(Long.toString(ID));
        verify(jdbc).update(contains("WHERE id=? AND status=?"), eq(4), eq(ID), eq(1));
        verify(jdbc).update(contains("INSERT INTO commerce_order_recovery"),
                eq("transition:" + ID + ":cancel:2"), eq(ID), eq(10L), eq(7L),
                anyString(), eq("RESTORE_PENDING"), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void merchantScopeRequiresOwnershipOfTheOrderShop() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet row = row();
        doAnswer(call -> List.of(((RowMapper<?>) call.getArgument(1)).mapRow(row, 0)))
                .when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        PlaceAuthorityClient place = mock(PlaceAuthorityClient.class);
        when(place.requireWriteAuthority(20L, 900L, false))
                .thenThrow(new CommerceApiException("COMMERCE_SHOP_FORBIDDEN", "Actor does not own the shop", 403));
        CommerceOrderService service = new CommerceOrderService(jdbc, null, null,
                JsonMapper.builderWithJackson2Defaults().build(), null, new CommerceRedisKeyspace(1, "v1"), null, place);

        assertThatThrownBy(() -> service.order(ID, 900, false, true))
                .isInstanceOfSatisfying(CommerceApiException.class, error -> assertThat(error.status()).isEqualTo(403));
        assertThatThrownBy(() -> service.transition(ID, 900, "use", false, true))
                .isInstanceOfSatisfying(CommerceApiException.class, error -> assertThat(error.status()).isEqualTo(403));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void merchantScopeCannotCancelOrRefund() {
        CommerceOrderService service = service(mock(JdbcTemplate.class), null, null);

        for (String action : List.of("cancel", "refund", "pay")) {
            assertThatThrownBy(() -> service.transition(ID, 900, action, false, true))
                    .isInstanceOfSatisfying(CommerceApiException.class,
                            error -> assertThat(error.code()).isEqualTo("COMMERCE_MERCHANT_ACTION_FORBIDDEN"));
        }
    }

    private CommerceOrderService service(JdbcTemplate jdbc, StringRedisTemplate redis, TransactionTemplate transactions) {
        return new CommerceOrderService(jdbc, redis, transactions, JsonMapper.builderWithJackson2Defaults().build(),
                null, new CommerceRedisKeyspace(1, "v1"), null, null);
    }

    private ResultSet row() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("id")).thenReturn(Long.toString(ID));
        when(row.getLong("user_id")).thenReturn(7L);
        when(row.getLong("voucher_id")).thenReturn(10L);
        when(row.getLong("shop_id")).thenReturn(20L);
        when(row.getString("shop_name_snapshot")).thenReturn("店铺");
        when(row.getString("voucher_title_snapshot")).thenReturn("双人券");
        when(row.getString("voucher_rules_snapshot")).thenReturn("预约后使用");
        when(row.getInt("pay_value_snapshot")).thenReturn(9900);
        when(row.getInt("actual_value_snapshot")).thenReturn(12000);
        when(row.getObject("pay_value_snapshot", Integer.class)).thenReturn(9900);
        when(row.getObject("actual_value_snapshot", Integer.class)).thenReturn(12000);
        when(row.getInt("status")).thenReturn(4);
        when(row.getInt("version")).thenReturn(2);
        when(row.getTimestamp("create_time")).thenReturn(CREATED);
        when(row.getTimestamp("update_time")).thenReturn(CREATED);
        return row;
    }
}
