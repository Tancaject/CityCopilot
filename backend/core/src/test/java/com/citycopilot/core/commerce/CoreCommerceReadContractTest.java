package com.citycopilot.core.commerce;

import com.citycopilot.contract.commerce.CommerceContracts.*;
import com.citycopilot.core.controller.AdminController;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.dto.UserDTO;
import com.citycopilot.core.service.*;
import com.citycopilot.core.tool.CoreToolRegistry;
import com.citycopilot.core.utils.UserHolder;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CoreCommerceReadContractTest {
    private MockRestServiceServer server;
    private CommercePortClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://commerce.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new CommercePortClient(builder.build(), "service-secret");
        UserDTO user = new UserDTO();
        user.setId(7L);
        user.setRole("admin");
        UserHolder.saveUser(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
        server.verify();
    }

    @Test
    void voucherResponsesKeepNumericIdMoneyDatesAndSparseWriteBody() {
        String voucher = """
                {"id":9007199254740993,"shopId":7,"title":"双人券","subTitle":null,"rules":null,
                 "payValue":9900,"actualValue":12000,"type":0,"status":2,"version":3,"stock":null,
                 "createTime":"2026-09-25T00:00:00.123+00:00","updateTime":"2026-09-25T00:00:00.123+00:00",
                 "beginTime":null,"endTime":null,"futureField":true}
                """;
        server.expect(requestTo("http://commerce.test/commerce/v1/vouchers?admin=false&shopId=7"))
                .andRespond(withSuccess("[" + voucher + "]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://commerce.test/commerce/v1/vouchers"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("X-CityCopilot-Admin", "true"))
                .andExpect(content().json("{\"shopId\":7,\"title\":\"双人券\",\"payValue\":9900,\"actualValue\":12000}"))
                .andRespond(withSuccess("{\"id\":9007199254740993,\"futureField\":true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://commerce.test/commerce/v1/vouchers/9007199254740993"))
                .andExpect(method(HttpMethod.POST)).andExpect(content().string("{\"status\":2}"))
                .andRespond(withSuccess(voucher, MediaType.APPLICATION_JSON));
        VoucherView read = (VoucherView) ((List<?>) client.vouchers(7L, false, null, null).getData()).get(0);
        assertThat(read.id()).isEqualTo(9007199254740993L);
        assertThat(read.payValue()).isEqualTo(9900);
        assertThat(read.stock()).isNull();
        assertThat(read.beginTime()).isNull();
        assertThat(read.createTime()).hasTime(Instant.parse("2026-09-25T00:00:00.123Z").toEpochMilli());
        assertThat(client.createVoucher(Map.of("shopId", 7, "title", "双人券", "payValue", 9900, "actualValue", 12000)).getData())
                .isEqualTo(9007199254740993L);
        assertThat(client.updateVoucher(9007199254740993L, Map.of("status", 2)).getData()).isEqualTo(read);
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withPropertyValues("spring.jackson.use-jackson2-defaults=true", "spring.jackson.default-property-inclusion=non_null")
                .run(context -> {
                    ObjectMapper json = context.getBean(ObjectMapper.class);
                    Map<String, Object> legacy = new java.util.LinkedHashMap<>(json.readValue(voucher, Map.class));
                    legacy.remove("futureField");
                    assertThat(json.readTree(json.writeValueAsBytes(read))).isEqualTo(json.readTree(json.writeValueAsBytes(legacy)));
                });
    }

    @Test
    void summaryAndDeadLetterQueriesRetainLongMetricsAndTimeFields() {
        Instant from = Instant.parse("2026-09-25T00:00:00Z");
        Instant to = from.plusSeconds(60);
        server.expect(requestTo("http://commerce.test/commerce/v1/ops/summary?from=" + from + "&to=" + to))
                .andRespond(withSuccess("""
                        {"orders":3000000000,"todayOrders":2,"unpaidOrders":3,"cancelledOrders":4,
                         "vouchers":5,"seckillVouchers":6,"activeVouchers":7,"futureField":true}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://commerce.test/commerce/v1/ops/dead-letters?limit=20"))
                .andExpect(header("X-CityCopilot-Ops-Authorized", "true"))
                .andExpect(header("X-CityCopilot-Actor", "user:7"))
                .andRespond(withSuccess("""
                        [{"recordId":"1-0","error":"unavailable","retryCount":3,
                          "createdAt":"2026-09-25T00:00:00.123+00:00","replayedAt":null,
                          "recoveryPhase":"REPAIR_REQUIRED","futureField":true}]
                        """, MediaType.APPLICATION_JSON));
        assertThat(client.operationsSummary(from, to).getData())
                .isEqualTo(new OperationsSummary(3000000000L, 2L, 3L, 4L, 5L, 6L, 7L));
        assertThat(client.deadLetters(null)).singleElement().satisfies(dead -> {
            assertThat(dead.recordId()).isEqualTo("1-0");
            assertThat(dead.retryCount()).isEqualTo(3);
            assertThat(dead.replayedAt()).isNull();
            assertThat(dead.createdAt()).hasTime(Instant.parse("2026-09-25T00:00:00.123Z").toEpochMilli());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"status\":\"already_replayed\",\"deadLetterRecordId\":\"1-0\"}",
            "{\"status\":\"replayed\",\"deadLetterRecordId\":\"1-0\",\"orderId\":\"9007199254740993\",\"state\":\"processing\",\"code\":\"COMMERCE_ORDER_ACCEPTED\",\"idempotencyKey\":\"key\"}",
            "{\"status\":\"replayed\",\"deadLetterRecordId\":\"1-0\",\"orderId\":\"9007199254740993\",\"state\":\"completed\",\"code\":\"IDEMPOTENT_REPLAY\",\"version\":3}"
    })
    void replayVariantsRoundTripWithTheExistingCoreNullPolicy(String response) {
        server.expect(requestTo("http://commerce.test/commerce/v1/ops/dead-letters/1-0/replay"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("X-CityCopilot-Ops-Authorized", "true"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        DeadLetterReplayResult result = client.replayDeadLetter("1-0");
        JsonMapper json = JsonMapper.builderWithJackson2Defaults()
                .changeDefaultPropertyInclusion(value -> value.withValueInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL))
                .build();
        assertThat(json.<JsonNode>valueToTree(result)).isEqualTo(json.readTree(response));
    }

    @Test
    void adminSummaryUsesTypedMetricsAndDoesNotReplaceMissingMetricsWithZero() {
        CommercePortClient port = mock(CommercePortClient.class);
        AdminController controller = new AdminController();
        ReflectionTestUtils.setField(controller, "commercePortClient", port);
        IShopService shops = mock(IShopService.class);
        IUserService users = mock(IUserService.class);
        IBlogService blogs = mock(IBlogService.class);
        OperationLogService logs = mock(OperationLogService.class);
        when(shops.countShops()).thenReturn(0L);
        when(shops.countShopsMissingGeo()).thenReturn(0L);
        when(shops.countShopsByGeoStatus(any())).thenReturn(0L);
        when(users.countUsers()).thenReturn(0L);
        when(blogs.countBlogs()).thenReturn(0L);
        when(blogs.countBlogsCreatedSince(any())).thenReturn(0L);
        doReturn(counts()).when(logs).query();
        ReflectionTestUtils.setField(controller, "shopService", shops);
        ReflectionTestUtils.setField(controller, "userService", users);
        ReflectionTestUtils.setField(controller, "blogService", blogs);
        ReflectionTestUtils.setField(controller, "operationLogService", logs);
        when(port.operationsSummary(any(), any())).thenReturn(Result.ok(new OperationsSummary(3000000000L, 2L, 3L, 4L, 5L, 6L, 7L)));
        assertThat(((Map<?, ?>) controller.opsSummary().getData()).get("orders")).isEqualTo(3000000000L);
        when(port.operationsSummary(any(), any())).thenReturn(Result.ok(new OperationsSummary(null, 2L, 3L, 4L, 5L, 6L, 7L)));
        assertThatThrownBy(controller::opsSummary).isInstanceOf(IllegalStateException.class)
                .hasMessage("Commerce operations summary is missing metric: orders");
    }

    @Test
    void recoveryToolsPublishConcreteRecordSchemasIncludingDateWireFormat() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(SeckillRecoveryService.class, () -> new SeckillRecoveryService(client));
            context.refresh();
            CoreToolRegistry registry = new CoreToolRegistry(context);
            registry.afterSingletonsInstantiated();
            var list = registry.definitions().stream().filter(tool -> tool.name().equals("list_seckill_dead_letters")).findFirst().orElseThrow();
            Map<?, ?> items = (Map<?, ?>) list.resultSchema().get("items");
            Map<?, ?> fields = (Map<?, ?>) items.get("properties");
            assertThat(((Map<?, ?>) fields.get("createdAt")).get("type")).isEqualTo("string");
            assertThat(((Map<?, ?>) fields.get("createdAt")).get("format")).isEqualTo("date-time");
            var replay = registry.definitions().stream().filter(tool -> tool.name().equals("replay_seckill_dead_letter")).findFirst().orElseThrow();
            assertThat(((Map<?, ?>) replay.resultSchema().get("properties")).containsKey("deadLetterRecordId")).isTrue();
            assertThat(replay.contractVersion()).isEqualTo(1);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> QueryChainWrapper<T> counts() {
        QueryChainWrapper<T> query = mock(QueryChainWrapper.class);
        doReturn(query).when(query).and(any());
        doReturn(query).when(query).eq(any(String.class), any());
        doReturn(query).when(query).ge(any(String.class), any());
        when(query.count()).thenReturn(0L);
        return query;
    }
}
