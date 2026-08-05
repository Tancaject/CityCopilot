package com.citycopilot.core.service.discovery;

import com.citycopilot.core.config.GeoProperties;
import com.citycopilot.core.config.ShopSearchProperties;
import com.citycopilot.core.entity.Shop;
import com.citycopilot.core.entity.ShopType;
import com.citycopilot.core.mapper.ShopMapper;
import com.citycopilot.core.mapper.ShopTypeMapper;
import com.citycopilot.core.config.ShopProjectionProperties;
import com.citycopilot.core.projection.ShopProjectionLeaseService;
import com.citycopilot.core.projection.ShopProjectionOutboxRepository;
import com.citycopilot.search.ShopSearchDocument;
import com.citycopilot.search.ShopSearchGateway;
import com.citycopilot.search.ShopSearchHit;
import com.citycopilot.search.ShopSearchRequest;
import com.citycopilot.search.ShopSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class ShopSearchProjectionServiceTest {

    private ShopSearchGateway gateway;
    private ShopMapper shopMapper;
    private ShopTypeMapper shopTypeMapper;
    private ShopSearchProjectionService service;
    private ShopSearchProperties searchProperties;

    @BeforeEach
    void setUp() {
        gateway = mock(ShopSearchGateway.class);
        shopMapper = mock(ShopMapper.class);
        shopTypeMapper = mock(ShopTypeMapper.class);

        GeoProperties geoProperties = new GeoProperties();
        geoProperties.setNearbyRadiusMeters(5000D);
        geoProperties.setRedisMaxCandidates(100);
        searchProperties = new ShopSearchProperties();
        searchProperties.setEnabled(true);
        searchProperties.setMode(ShopSearchProperties.Mode.ELASTICSEARCH);
        service = new ShopSearchProjectionService(
                gateway,
                shopMapper,
                shopTypeMapper,
                geoProperties,
                searchProperties
        );
        when(shopTypeMapper.selectList(null)).thenReturn(List.of());
        when(shopMapper.selectList(null)).thenReturn(List.of());
        service.rebuildAll();
        clearInvocations(gateway, shopMapper, shopTypeMapper);
    }

    @Test
    void searchPreservesElasticsearchOrderAfterMysqlHydrationAndValidatesCurrentRows() {
        when(gateway.search(any())).thenReturn(ShopSearchResult.available(List.of(
                hit(2L),
                hit(1L)
        ), 2));
        when(shopMapper.selectBatchIds(any(Collection.class))).thenReturn(List.of(
                shop(1L, 7L, "first", 120.002D, 30D, "verified"),
                shop(2L, 7L, "second", 120.001D, 30D, "verified")
        ));

        ShopDiscoveryQuery query = new ShopDiscoveryQuery()
                .setKeyword(" coffee ")
                .setPreferredCategory(" cafe ")
                .setTypeId(7)
                .setCurrent(1)
                .setSize(10)
                .setX(120D)
                .setY(30D);

        ShopSearchProjectionService.SearchCandidates result = service.search(query);

        assertTrue(result.available());
        assertEquals(List.of(2L, 1L), result.candidates().stream()
                .map(candidate -> candidate.shop().getId())
                .toList());
        assertEquals(List.of(0, 1), result.candidates().stream()
                .map(ShopSearchProjectionService.SearchCandidate::searchRank)
                .toList());
        assertEquals(2L, result.total());

        ArgumentCaptor<ShopSearchRequest> request = ArgumentCaptor.forClass(ShopSearchRequest.class);
        verify(gateway).search(request.capture());
        assertEquals("coffee", request.getValue().keyword());
        assertEquals("cafe", request.getValue().preferredCategory());
        assertEquals(7L, request.getValue().typeId());
        assertEquals(120D, request.getValue().longitude());
        assertEquals(30D, request.getValue().latitude());
        assertEquals(5000D, request.getValue().radiusMeters());
        assertEquals(0, request.getValue().offset());
        assertEquals(10, request.getValue().size());
    }

    @Test
    void staleElasticsearchHitsReturnUnavailableSoCallerCanFallback() {
        when(gateway.search(any())).thenReturn(ShopSearchResult.available(List.of(
                hit(1L),
                hit(2L)
        ), 2));
        when(shopMapper.selectBatchIds(any(Collection.class))).thenReturn(List.of(
                shop(1L, 7L, "current", 120.001D, 30D, "verified")
        ));

        ShopSearchProjectionService.SearchCandidates result = service.search(
                new ShopDiscoveryQuery()
                        .setKeyword("coffee")
                        .setX(120D)
                        .setY(30D)
        );

        assertFalse(result.available());
        assertEquals(ShopSearchResult.INDEX_STALE, result.unavailableReason());
        assertTrue(result.candidates().isEmpty());
        assertTrue(service.needsReconciliation());
    }

    @Test
    void duplicateElasticsearchHitsReturnUnavailableInsteadOfFalseCurrentResult() {
        when(gateway.search(any())).thenReturn(ShopSearchResult.available(List.of(
                hit(1L),
                hit(1L)
        ), 2));
        when(shopMapper.selectBatchIds(any(Collection.class))).thenReturn(List.of(
                shop(1L, 7L, "current", 120D, 30D, "verified")
        ));

        ShopSearchProjectionService.SearchCandidates result = service.search(
                new ShopDiscoveryQuery().setKeyword("coffee")
        );

        assertFalse(result.available());
        assertEquals(ShopSearchResult.INDEX_STALE, result.unavailableReason());
    }

    @Test
    void availableEmptyResultDoesNotBecomeFallbackSignal() {
        when(gateway.search(any())).thenReturn(ShopSearchResult.empty());

        ShopSearchProjectionService.SearchCandidates result = service.search(
                new ShopDiscoveryQuery().setKeyword("nothing").setCurrent(1).setSize(10)
        );

        assertTrue(result.available());
        assertTrue(result.candidates().isEmpty());
        assertEquals(0L, result.total());
        verify(shopMapper, never()).selectBatchIds(any(Collection.class));
    }

    @Test
    void unavailableResultIsExplicitAndSkipsMysqlHydration() {
        when(gateway.search(any())).thenReturn(ShopSearchResult.unavailable(ShopSearchResult.INDEX_NOT_READY));

        ShopSearchProjectionService.SearchCandidates result = service.search(
                new ShopDiscoveryQuery().setPreferredCategory("coffee")
        );

        assertFalse(result.available());
        assertEquals(ShopSearchResult.INDEX_NOT_READY, result.unavailableReason());
        verify(shopMapper, never()).selectBatchIds(any(Collection.class));
    }

    @Test
    void rebuildMapsAllProjectionFieldsAndClearsReconcileFlagOnlyOnSuccess() {
        LocalDateTime updateTime = LocalDateTime.of(2026, 8, 4, 12, 30);
        Shop shop = shop(8L, 7L, "Coffee House", 120.1D, 30.2D, "verified")
                .setArea("West Lake")
                .setAddress("1 Lake Road")
                .setAvgPrice(45L)
                .setScore(48)
                .setSold(123)
                .setOpenHours("09:00-22:00")
                .setUpdateTime(updateTime);
        when(shopTypeMapper.selectList(null)).thenReturn(List.of(new ShopType().setId(7L).setName("Coffee")));
        when(shopMapper.selectList(null)).thenReturn(List.of(
                shop,
                new Shop().setId(10L).setName("Missing Latitude").setX(120D),
                new Shop().setId(9L).setName("   ")
        ));
        when(gateway.isReady()).thenReturn(true);

        assertEquals(2, service.rebuildAll());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<ShopSearchDocument>> documents = ArgumentCaptor.forClass(Collection.class);
        verify(gateway).rebuild(documents.capture());
        ShopSearchDocument document = documents.getValue().stream()
                .filter(candidate -> candidate.id().equals(8L))
                .findFirst()
                .orElseThrow();
        assertEquals(8L, document.id());
        assertEquals("Coffee House", document.name());
        assertEquals(7L, document.typeId());
        assertEquals("Coffee", document.typeName());
        assertEquals("West Lake", document.area());
        assertEquals("1 Lake Road", document.address());
        assertEquals(120.1D, document.longitude());
        assertEquals(30.2D, document.latitude());
        assertEquals(45L, document.avgPrice());
        assertEquals(48, document.score());
        assertEquals(123, document.sold());
        assertEquals("09:00-22:00", document.openHours());
        assertEquals("verified", document.geoStatus());
        assertEquals(updateTime, document.updateTime());
        ShopSearchDocument missingLatitude = documents.getValue().stream()
                .filter(candidate -> candidate.id().equals(10L))
                .findFirst()
                .orElseThrow();
        assertNull(missingLatitude.longitude());
        assertNull(missingLatitude.latitude());
        assertFalse(service.needsReconciliation());
    }

    @Test
    void failedBestEffortUpsertDoesNotEscapeAndMarksProjectionForReconciliation() {
        when(shopTypeMapper.selectList(null)).thenReturn(List.of());
        when(shopMapper.selectList(null)).thenReturn(List.of());
        when(gateway.isReady()).thenReturn(true);
        service.rebuildAll();
        doThrow(new IllegalStateException("elasticsearch unavailable"))
                .when(gateway).upsert(any(ShopSearchDocument.class));
        when(shopMapper.selectById(1L)).thenReturn(
                shop(1L, 7L, "Coffee", 120D, 30D, "verified")
        );

        assertDoesNotThrow(() -> service.upsertLatestBestEffort(1L));

        assertTrue(service.needsReconciliation());
        ShopSearchProjectionService.SearchCandidates nextSearch = service.search(
                new ShopDiscoveryQuery().setKeyword("coffee")
        );
        assertFalse(nextSearch.available());
        assertEquals(ShopSearchResult.INDEX_STALE, nextSearch.unavailableReason());
        verify(gateway, never()).search(any());
    }

    @Test
    void incrementalUpsertReloadsLatestCommittedMysqlRow() {
        Shop latest = shop(1L, 7L, "Latest Name", 120D, 30D, "verified")
                .setAvgPrice(88L)
                .setScore(49);
        when(shopMapper.selectById(1L)).thenReturn(latest);
        when(shopTypeMapper.selectById(7L)).thenReturn(new ShopType().setId(7L).setName("Coffee"));

        service.upsertLatestBestEffort(1L);

        ArgumentCaptor<ShopSearchDocument> document = ArgumentCaptor.forClass(ShopSearchDocument.class);
        verify(gateway).upsert(document.capture());
        assertEquals("Latest Name", document.getValue().name());
        assertEquals(88L, document.getValue().avgPrice());
        assertEquals(49, document.getValue().score());
        assertEquals("Coffee", document.getValue().typeName());
    }

    @Test
    void disabledReadModeDoesNotSearchOrProject() {
        ShopSearchProperties disabled = new ShopSearchProperties();
        ShopSearchProjectionService disabledService = new ShopSearchProjectionService(
                gateway,
                shopMapper,
                shopTypeMapper,
                new GeoProperties(),
                disabled
        );

        ShopSearchProjectionService.SearchCandidates result = disabledService.search(
                new ShopDiscoveryQuery().setKeyword("coffee")
        );
        disabledService.upsertLatestBestEffort(1L);

        assertFalse(result.available());
        assertEquals(ShopSearchResult.SEARCH_DISABLED, result.unavailableReason());
        assertNull(result.candidates().stream().findFirst().orElse(null));
        verify(gateway, never()).search(any());
        verify(gateway, never()).upsert(any());
    }

    @Test
    void rebuildCatchesUpClosedOutboxWatermarksBeforePublishingGeneration() {
        ShopProjectionLeaseService leases = mock(ShopProjectionLeaseService.class);
        ShopProjectionLeaseService.Lease lease = mock(ShopProjectionLeaseService.Lease.class);
        ShopProjectionOutboxRepository outbox = mock(ShopProjectionOutboxRepository.class);
        ReflectionTestUtils.setField(service, "projectionLeaseService", leases);
        ReflectionTestUtils.setField(service, "projectionProperties", new ShopProjectionProperties());
        ReflectionTestUtils.setField(service, "projectionOutboxRepository", outbox);
        when(leases.tryAcquire(any(), any())).thenReturn(Optional.of(lease));
        when(lease.executeCutoverFenced(any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        when(outbox.highWatermark()).thenReturn(10L, 11L, 12L);
        when(outbox.changesAfter(10L, 11L, 500)).thenReturn(List.of(
                new ShopProjectionOutboxRepository.ProjectionChange(11L, 1L, 2L)));
        when(outbox.changesAfter(11L, 12L, 500)).thenReturn(List.of(
                new ShopProjectionOutboxRepository.ProjectionChange(12L, 1L, 3L)));
        Shop base = shop(1L, 7L, "base", 120D, 30D, "verified").setProjectionVersion(1L);
        Shop current = shop(1L, 7L, "current", 121D, 31D, "verified").setProjectionVersion(3L);
        when(shopMapper.selectList(null)).thenReturn(List.of(base));
        when(shopMapper.selectBatchIds(any(Collection.class))).thenReturn(List.of(current));
        when(shopTypeMapper.selectBatchIds(any(Collection.class))).thenReturn(List.of(
                new ShopType().setId(7L).setName("Coffee")));
        ShopSearchGateway.PendingGeneration pending = mock(ShopSearchGateway.PendingGeneration.class);
        doAnswer(invocation -> {
            ShopSearchGateway.RebuildCutover cutover = invocation.getArgument(1);
            cutover.execute(pending);
            return null;
        }).when(gateway).rebuild(any(Collection.class), any(ShopSearchGateway.RebuildCutover.class));

        assertEquals(1, service.rebuildAll());

        InOrder publicationOrder = inOrder(pending);
        publicationOrder.verify(pending, times(2)).upsertAll(any(Collection.class));
        publicationOrder.verify(pending).publish();
        verify(outbox, times(3)).highWatermark();
        verify(outbox).changesAfter(10L, 11L, 500);
        verify(outbox).changesAfter(11L, 12L, 500);
    }

    @Test
    void staleApiReplicaRecoversThroughBackedOffSharedAliasProbe() {
        searchProperties.setRecoveryProbeIntervalMs(0L);
        when(gateway.search(any()))
                .thenReturn(ShopSearchResult.unavailable(ShopSearchResult.SEARCH_UNAVAILABLE))
                .thenReturn(ShopSearchResult.empty());
        when(gateway.probeSharedReadiness()).thenReturn(true);

        ShopSearchProjectionService.SearchCandidates failed = service.search(
                new ShopDiscoveryQuery().setKeyword("coffee"));
        ShopSearchProjectionService.SearchCandidates recovered = service.search(
                new ShopDiscoveryQuery().setKeyword("coffee"));

        assertFalse(failed.available());
        assertTrue(recovered.available());
        verify(gateway).probeSharedReadiness();
        verify(gateway, times(2)).search(any());
    }

    private ShopSearchHit hit(Long id) {
        return new ShopSearchHit(ShopSearchDocument.builder()
                .id(id)
                .name("indexed-" + id)
                .build(), 1D);
    }

    private Shop shop(Long id, Long typeId, String name, double x, double y, String geoStatus) {
        return new Shop()
                .setId(id)
                .setTypeId(typeId)
                .setName(name)
                .setX(x)
                .setY(y)
                .setGeoStatus(geoStatus);
    }
}
