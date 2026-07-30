package com.citycopilot.commerce;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommerceVoucherServiceTest {

    @Test
    void acceptsCoreLocalDateTimeAndIsoInstantFormats() {
        ZoneId singapore = ZoneId.of("Asia/Singapore");

        Timestamp local = CommerceVoucherService.timestamp(
                "2026-07-30 09:30:00",
                null,
                singapore
        );
        Timestamp instant = CommerceVoucherService.timestamp(
                "2026-07-30T01:30:00Z",
                null,
                singapore
        );

        assertThat(local.toInstant()).isEqualTo(Instant.parse("2026-07-30T01:30:00Z"));
        assertThat(instant.toInstant()).isEqualTo(Instant.parse("2026-07-30T01:30:00Z"));
    }

    @Test
    void rejectsMalformedVoucherTimeWithAStableClientError() {
        assertThatThrownBy(() -> CommerceVoucherService.timestamp(
                "not-a-time",
                null,
                ZoneId.of("Asia/Singapore")
        ))
                .isInstanceOf(CommerceApiException.class)
                .hasMessage("Voucher time must be an ISO-8601 or yyyy-MM-dd HH:mm:ss value");
    }

    @Test
    void rejectsInvalidSeckillVoucherBeforeWriting() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlaceAuthorityClient place = mock(PlaceAuthorityClient.class);
        CommerceOrderIdGenerator ids = mock(CommerceOrderIdGenerator.class);
        CommerceRedisInventoryBootstrap inventory = mock(CommerceRedisInventoryBootstrap.class);
        when(place.requireWriteAuthority(1L, 7L, true))
                .thenReturn(Map.of("name", "Demo", "ownerUserId", 7L));
        CommerceVoucherService service = new CommerceVoucherService(jdbc, place, ids, inventory);

        assertThatThrownBy(() -> service.create(Map.of(
                "shopId", 1L,
                "title", "Invalid",
                "payValue", 100,
                "actualValue", 50,
                "type", 1,
                "stock", 10,
                "beginTime", "2026-07-30T00:00:00Z",
                "endTime", "2026-07-31T00:00:00Z"
        ), 7L, true))
                .isInstanceOf(CommerceApiException.class)
                .hasMessage("Voucher amounts are invalid");

        org.mockito.Mockito.verifyNoInteractions(ids);
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    @Test
    void allocatesVoucherIdsFromTheCommerceGenerator() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlaceAuthorityClient place = mock(PlaceAuthorityClient.class);
        CommerceOrderIdGenerator ids = mock(CommerceOrderIdGenerator.class);
        CommerceRedisInventoryBootstrap inventory = mock(CommerceRedisInventoryBootstrap.class);
        when(place.requireWriteAuthority(1L, 7L, true))
                .thenReturn(Map.of("name", "Demo", "ownerUserId", 7L));
        when(ids.nextVoucherId()).thenReturn(123L);
        CommerceVoucherService service = new CommerceVoucherService(jdbc, place, ids, inventory);

        long created = service.create(Map.of(
                "shopId", 1L,
                "title", "Valid",
                "payValue", 50,
                "actualValue", 100,
                "type", 1,
                "stock", 10,
                "beginTime", "2026-07-30T00:00:00Z",
                "endTime", "2026-07-31T00:00:00Z"
        ), 7L, true);

        assertThat(created).isEqualTo(123L);
        verify(ids).nextVoucherId();
        verify(inventory).initializeNewVoucher(123L, 10);
        verify(jdbc, org.mockito.Mockito.times(3))
                .update(anyString(), any(Object[].class));
    }

    @Test
    void rejectsDirectStockChangesOnAnExistingVoucher() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlaceAuthorityClient place = mock(PlaceAuthorityClient.class);
        CommerceOrderIdGenerator ids = mock(CommerceOrderIdGenerator.class);
        CommerceRedisInventoryBootstrap inventory = mock(CommerceRedisInventoryBootstrap.class);
        when(jdbc.queryForMap(
                org.mockito.ArgumentMatchers.contains("SELECT * FROM commerce_voucher"),
                org.mockito.ArgumentMatchers.eq(123L)
        )).thenReturn(Map.of(
                "id", 123L,
                "shop_id", 1L,
                "title", "Existing",
                "pay_value", 50,
                "actual_value", 100,
                "type", 1,
                "status", 1
        ));
        when(jdbc.queryForMap(
                org.mockito.ArgumentMatchers.contains("FROM commerce_seckill_voucher"),
                org.mockito.ArgumentMatchers.eq(123L)
        )).thenReturn(Map.of(
                "stock", 10,
                "begin_time", Timestamp.from(Instant.parse("2026-07-30T00:00:00Z")),
                "end_time", Timestamp.from(Instant.parse("2026-07-31T00:00:00Z"))
        ));
        CommerceVoucherService service = new CommerceVoucherService(jdbc, place, ids, inventory);

        assertThatThrownBy(() -> service.update(123L, Map.of("stock", 11), 7L, true))
                .isInstanceOf(CommerceApiException.class)
                .hasMessage("Direct stock changes are disabled; use fenced inventory maintenance");
    }
}
