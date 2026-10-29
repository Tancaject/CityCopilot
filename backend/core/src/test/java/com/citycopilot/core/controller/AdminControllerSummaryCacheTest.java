package com.citycopilot.core.controller;

import com.citycopilot.contract.commerce.CommerceContracts.OperationsSummary;
import com.citycopilot.core.commerce.CommercePortClient;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.service.IBlogService;
import com.citycopilot.core.service.IShopService;
import com.citycopilot.core.service.IUserService;
import com.citycopilot.core.service.OperationLogService;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminControllerSummaryCacheTest {
    private final CommercePortClient commerce = mock(CommercePortClient.class);
    private final AdminController controller = new AdminController();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "commercePortClient", commerce);
        ReflectionTestUtils.setField(controller, "shopService", mock(IShopService.class));
        ReflectionTestUtils.setField(controller, "userService", mock(IUserService.class));
        ReflectionTestUtils.setField(controller, "blogService", mock(IBlogService.class));
        OperationLogService logs = mock(OperationLogService.class);
        // eq/ge 的泛型返回类型擦除为 Object，RETURNS_SELF 不生效；链式条件一律返回自身，count 返回 0。
        QueryChainWrapper<?> chain = mock(QueryChainWrapper.class,
                invocation -> "count".equals(invocation.getMethod().getName()) ? 0L : invocation.getMock());
        doReturn(chain).when(logs).query();
        ReflectionTestUtils.setField(controller, "operationLogService", logs);
    }

    @Test
    void successfulSummaryIsServedFromCacheWithoutRescanningCommerce() {
        when(commerce.operationsSummary(any(), any()))
                .thenReturn(Result.ok(new OperationsSummary(10L, 1L, 2L, 3L, 4L, 5L, 6L)));

        Result first = controller.opsSummary();
        Result second = controller.opsSummary();

        assertThat(first.getSuccess()).isTrue();
        assertThat(((Map<?, ?>) second.getData()).get("orders")).isEqualTo(10L);
        verify(commerce, times(1)).operationsSummary(any(), any());
    }

    @Test
    void failedSummaryIsNotCached() {
        when(commerce.operationsSummary(any(), any()))
                .thenReturn(Result.fail("COMMERCE_UNAVAILABLE", "down"))
                .thenReturn(Result.ok(new OperationsSummary(10L, 1L, 2L, 3L, 4L, 5L, 6L)));

        assertThat(controller.opsSummary().getSuccess()).isFalse();
        assertThat(controller.opsSummary().getSuccess()).isTrue();
        verify(commerce, times(2)).operationsSummary(any(), any());
    }
}
