package com.citycopilot.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShopSearchResultTest {

    @Test
    void distinguishesAvailableEmptyResultFromUnavailableSearch() {
        ShopSearchResult empty = ShopSearchResult.empty();
        ShopSearchResult unavailable = ShopSearchResult.unavailable(ShopSearchResult.INDEX_NOT_READY);

        assertThat(empty.available()).isTrue();
        assertThat(empty.availability()).isEqualTo(ShopSearchResult.Availability.AVAILABLE);
        assertThat(empty.hits()).isEmpty();
        assertThat(empty.unavailableReason()).isNull();

        assertThat(unavailable.available()).isFalse();
        assertThat(unavailable.availability()).isEqualTo(ShopSearchResult.Availability.UNAVAILABLE);
        assertThat(unavailable.hits()).isEmpty();
        assertThat(unavailable.unavailableReason()).isEqualTo(ShopSearchResult.INDEX_NOT_READY);
    }
}
