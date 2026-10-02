package com.citycopilot.search;

import co.elastic.clients.elasticsearch.core.SearchRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ElasticsearchShopSearchGatewayTest {

    @Test
    void returnsAvailableEmptyResultWhenIndexIsReadyAndHasNoMatches() {
        FakeClient client = new FakeClient();
        client.aliasExists = true;
        ElasticsearchShopSearchGateway gateway = gateway(client);

        ShopSearchResult result = gateway.search(ShopSearchRequest.builder().keyword("不存在").build());

        assertThat(result.available()).isTrue();
        assertThat(result.hits()).isEmpty();
        assertThat(result.total()).isZero();
    }

    @Test
    void failedIncrementalWriteMarksProjectionStaleUntilSuccessfulRebuild() {
        FakeClient client = new FakeClient();
        client.aliasExists = true;
        client.failIndex = true;
        ElasticsearchShopSearchGateway gateway = gateway(client);

        assertThatThrownBy(() -> gateway.upsert(document(1L)))
                .isInstanceOf(ShopSearchException.class);
        assertThat(gateway.isReady()).isFalse();
        assertThat(gateway.search(ShopSearchRequest.builder().build()).unavailableReason())
                .isEqualTo(ShopSearchResult.INDEX_STALE);

        gateway.rebuild(List.of(document(1L), document(2L), document(3L)));

        assertThat(gateway.isReady()).isTrue();
        assertThat(client.aliasRemovalPattern).isEqualTo("citycopilot-shop-*");
        assertThat(client.operations).containsExactly(
                "index-failed",
                "create:shops-generation-1",
                "bulk:shops-generation-1:2",
                "bulk:shops-generation-1:1",
                "refresh:shops-generation-1",
                "alias:custom-alias->shops-generation-1");
    }

    @Test
    void rebuildFailureLeavesProjectionStaleAndDoesNotSwitchAlias() {
        FakeClient client = new FakeClient();
        client.aliasExists = true;
        client.failBulk = true;
        ElasticsearchShopSearchGateway gateway = gateway(client);

        assertThatThrownBy(() -> gateway.rebuild(List.of(document(1L))))
                .isInstanceOf(ShopSearchException.class);

        assertThat(gateway.isReady()).isFalse();
        assertThat(client.operations).containsExactly(
                "create:shops-generation-1",
                "bulk-failed:shops-generation-1");
    }

    @Test
    void fencingGuardRunsImmediatelyBeforeAliasSwitch() {
        FakeClient client = new FakeClient();
        ElasticsearchShopSearchGateway gateway = gateway(client);

        gateway.rebuild(List.of(document(1L)), generation -> {
            client.operations.add("fence-check");
            generation.publish();
        });

        assertThat(client.operations).containsExactly(
                "create:shops-generation-1",
                "bulk:shops-generation-1:1",
                "fence-check",
                "refresh:shops-generation-1",
                "alias:custom-alias->shops-generation-1");
    }

    @Test
    void catchUpWritesTargetPendingGenerationBeforeAliasSwitch() {
        FakeClient client = new FakeClient();
        ElasticsearchShopSearchGateway gateway = gateway(client);

        gateway.rebuild(List.of(document(1L)), generation -> {
            generation.upsertAll(List.of(document(2L)));
            generation.publish();
        });

        assertThat(client.operations).containsExactly(
                "create:shops-generation-1",
                "bulk:shops-generation-1:1",
                "bulk:shops-generation-1:1",
                "refresh:shops-generation-1",
                "alias:custom-alias->shops-generation-1");
    }

    @Test
    void sharedAliasProbeClearsReplicaLocalTransientWriteFailure() {
        FakeClient client = new FakeClient();
        client.aliasExists = true;
        client.failIndex = true;
        ElasticsearchShopSearchGateway gateway = gateway(client);
        assertThatThrownBy(() -> gateway.upsert(document(1L)))
                .isInstanceOf(ShopSearchException.class);
        assertThat(gateway.isReady()).isFalse();

        assertThat(gateway.probeSharedReadiness()).isTrue();

        assertThat(gateway.isReady()).isTrue();
    }

    private ElasticsearchShopSearchGateway gateway(FakeClient client) {
        CityCopilotSearchProperties properties = new CityCopilotSearchProperties();
        properties.setIndexAlias("custom-alias");
        properties.setBulkBatchSize(2);
        return new ElasticsearchShopSearchGateway(
                client, properties, new ShopSearchRequestFactory(), () -> "shops-generation-1");
    }

    private ShopSearchDocument document(long id) {
        return ShopSearchDocument.builder()
                .id(id)
                .name("咖啡店 " + id)
                .typeId(7L)
                .typeName("咖啡馆")
                .area("陆家嘴")
                .address("世纪大道")
                .longitude(121.5)
                .latitude(31.2)
                .avgPrice(45L)
                .score(46)
                .sold(100)
                .openHours("08:00-22:00")
                .geoStatus("verified")
                .updateTime(LocalDateTime.of(2026, 8, 4, 12, 0))
                .build();
    }

    private static final class FakeClient implements ShopSearchClient {
        private final List<String> operations = new ArrayList<>();
        private boolean aliasExists;
        private boolean failIndex;
        private boolean failBulk;
        private String aliasRemovalPattern;
        private String activeIndex;

        @Override
        public boolean aliasExists(String alias) {
            return aliasExists;
        }

        @Override
        public SearchPage search(SearchRequest request) {
            return new SearchPage(List.of(), 0);
        }

        @Override
        public void index(String alias, ElasticsearchShopDocument document) throws IOException {
            if (failIndex) {
                operations.add("index-failed");
                failIndex = false;
                throw new IOException("simulated write failure");
            }
            operations.add("index:" + alias + ":" + document.id());
        }

        @Override
        public void createIndex(String index) {
            operations.add("create:" + index);
        }

        @Override
        public void bulkIndex(String index, List<ElasticsearchShopDocument> documents) throws IOException {
            if (failBulk) {
                operations.add("bulk-failed:" + index);
                throw new IOException("simulated bulk failure");
            }
            operations.add("bulk:" + index + ":" + documents.size());
        }

        @Override
        public void refresh(String index) {
            operations.add("refresh:" + index);
        }

        @Override
        public void switchAlias(String alias, String previousIndexPattern, String newIndex) {
            aliasRemovalPattern = previousIndexPattern;
            operations.add("alias:" + alias + "->" + newIndex);
            aliasExists = true;
            activeIndex = newIndex;
        }

        @Override
        public List<String> aliasTargets(String alias) {
            return activeIndex == null ? List.of() : List.of(activeIndex);
        }

        @Override
        public boolean deleteUnaliasedIndex(String index) {
            return !index.equals(activeIndex);
        }
    }
}
