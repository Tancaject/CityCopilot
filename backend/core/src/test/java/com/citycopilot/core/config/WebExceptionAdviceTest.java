package com.citycopilot.core.config;

import com.citycopilot.core.dto.Result;
import com.citycopilot.core.utils.CacheRebuildBusyException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class WebExceptionAdviceTest {

    @Test
    void cacheRebuildContentionReturnsRetryableServiceUnavailable() {
        WebExceptionAdvice advice = new WebExceptionAdvice();

        ResponseEntity<Result> response = advice.handleCacheRebuildBusy(
                new CacheRebuildBusyException("店铺缓存正在重建，请稍后重试")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("CACHE_REBUILD_BUSY");
    }
}
