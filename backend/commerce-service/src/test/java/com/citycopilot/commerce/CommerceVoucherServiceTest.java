package com.citycopilot.commerce;

import com.citycopilot.contract.commerce.CommerceContracts.VoucherCreated;
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
        CommerceRedisCampaignMetadata metadata = mock(CommerceRedisCampaignMetadata.class);
        when(place.requireWriteAuthority(1L, 7L, true))
                .thenReturn(Map.of("name", "Demo", "ownerUserId", 7L));
        CommerceVoucherService service = new CommerceVoucherService(jdbc, place, ids, inventory, metadata, mock(CommerceReadCache.class));

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
    void rejectsClientSuppliedVoucherIdBeforeWriting() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlaceAuthorityClient place = mock(PlaceAuthorityClient.class);
        CommerceOrderIdGenerator ids = mock(CommerceOrderIdGenerator.class);
        CommerceRedisInventoryBootstrap inventory = mock(CommerceRedisInventoryBootstrap.class);
        CommerceRedisCampaignMetadata metadata = mock(CommerceRedisCampaignMetadata.class);
        when(place.requireWriteAuthority(1L, 7L, false))
                .thenReturn(Map.of("name", "Demo", "ownerUserId", 7L));
        CommerceVoucherService service = new CommerceVoucherService(jdbc, place, ids, inventory, metadata, mock(CommerceReadCache.class));

        assertThatThrownBy(() -> service.create(Map.of(
                "id", 999L,
                "shopId", 1L,
                "title", "Valid",
                "payValue", 50,
                "actualValue", 100
        ), 7L, false))
                .isInstanceOf(CommerceApiException.class)
                .hasMessage("id is assigned by Commerce");

        org.mockito.Mockito.verifyNoInteractions(ids);
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    @Test
    void allocatesVoucherIdsFromTheCommerceGenerator() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlaceAuthorityClient place = mock(PlaceAuthorityClient.class);
        CommerceOrderIdGenerator ids = mock(CommerceOrderIdGenerator.class);
        CommerceRedisInventoryBootstrap inventory = mock(CommerceRedisInventoryBootstrap.class);
        CommerceRedisCampaignMetadata metadata = mock(CommerceRedisCampaignMetadata.class);
        when(place.requireWriteAuthority(1L, 7L, true))
                .thenReturn(Map.of("name", "Demo", "ownerUserId", 7L));
        when(ids.nextVoucherId()).thenReturn(123L);
        CommerceVoucherService service = new CommerceVoucherService(jdbc, place, ids, inventory, metadata, mock(CommerceReadCache.class));

        VoucherCreated created = service.create(Map.of(
                "shopId", 1L,
                "title", "Valid",
                "payValue", 50,
                "actualValue", 100,
                "type", 1,
                "stock", 10,
                "beginTime", "2026-07-30T00:00:00Z",
                "endTime", "2026-07-31T00:00:00Z"
        ), 7L, true);

        assertThat(created.id()).isEqualTo(123L);
        verify(ids).nextVoucherId();
        verify(inventory).initializeNewVoucher(123L, 10);
        verify(metadata).refreshAfterCommit(123L);
        verify(jdbc, org.mockito.Mockito.times(3))
                .update(anyString(), any(Object[].class));
    }

    @Test
    void rejectsDirectStockChangesOnAnExistingVoucher() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlaceAuthorityClient place = mock(PlaceAuthorityClient.class);
        CommerceOrderIdGenerator ids = mock(CommerceOrderIdGenerator.class);
        CommerceRedisInventoryBootstrap inventory = mock(CommerceRedisInventoryBootstrap.class);
        CommerceRedisCampaignMetadata metadata = mock(CommerceRedisCampaignMetadata.class);
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
        CommerceVoucherService service = new CommerceVoucherService(jdbc, place, ids, inventory, metadata, mock(CommerceReadCache.class));

        assertThatThrownBy(() -> service.update(123L, Map.of("stock", 11), 7L, true))
                .isInstanceOf(CommerceApiException.class)
                .hasMessage("Direct stock changes are disabled; use fenced inventory maintenance");
    }

    @Test
    void closesAdmissionBeforeASeckillUpdateAndRefreshesMetadataAfterward() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlaceAuthorityClient place = mock(PlaceAuthorityClient.class);
        CommerceOrderIdGenerator ids = mock(CommerceOrderIdGenerator.class);
        CommerceRedisInventoryBootstrap inventory = mock(CommerceRedisInventoryBootstrap.class);
        CommerceRedisCampaignMetadata metadata = mock(CommerceRedisCampaignMetadata.class);
        when(metadata.invalidateBeforeChange(123L)).thenReturn(7L);
        Map<String, Object> existing = Map.of(
                "id", 123L,
                "shop_id", 1L,
                "title", "Existing",
                "pay_value", 50,
                "actual_value", 100,
                "type", 1,
                "status", 1
        );
        Map<String, Object> seckill = Map.of(
                "stock", 10,
                "begin_time", Timestamp.from(Instant.parse("2026-07-30T00:00:00Z")),
                "end_time", Timestamp.from(Instant.parse("2026-07-31T00:00:00Z"))
        );
        when(jdbc.queryForMap(
                org.mockito.ArgumentMatchers.contains("SELECT * FROM commerce_voucher"),
                org.mockito.ArgumentMatchers.eq(123L)
        )).thenReturn(existing);
        when(jdbc.queryForMap(
                org.mockito.ArgumentMatchers.contains("FROM commerce_seckill_voucher"),
                org.mockito.ArgumentMatchers.eq(123L)
        )).thenReturn(seckill);
        when(jdbc.queryForMap(
                org.mockito.ArgumentMatchers.contains("SELECT v.*,s.stock"),
                org.mockito.ArgumentMatchers.eq(123L)
        )).thenReturn(Map.ofEntries(
                Map.entry("id", 123L),
                Map.entry("shop_id", 1L),
                Map.entry("title", "Existing"),
                Map.entry("pay_value", 50),
                Map.entry("actual_value", 100),
                Map.entry("type", 1),
                Map.entry("status", 2),
                Map.entry("stock", 10),
                Map.entry("begin_time", seckill.get("begin_time")),
                Map.entry("end_time", seckill.get("end_time"))
        ));
        CommerceVoucherService service = new CommerceVoucherService(
                jdbc, place, ids, inventory, metadata, mock(CommerceReadCache.class)
        );

        service.update(123L, Map.of("status", 2), 7L, true);

        verify(metadata).invalidateBeforeChange(123L);
        verify(metadata).refreshAfterCommit(123L, 7L);
    }
}
