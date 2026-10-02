package com.citycopilot.agent.ops.v2;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the actual persisted-plan read path without a database or new approvals. */
class OpsPlanJacksonCompatibilityTest {
    @Test void readsLegacyStepsJsonWithoutRecomputingStoredApprovalHash() throws Exception {
        String storedSteps;
        try (var stream = getClass().getResourceAsStream("/compatibility/jackson2/ops-steps-v1.json")) {
            assertNotNull(stream); storedSteps = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        String oldHash = "a3d0cc8a928cad5ea18d13cb0a91c94c019094f95d20a2eab02a772f84e67c12";
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), eq("case-legacy"))).thenReturn(List.of("plan-legacy"));
        ResultSet row = mock(ResultSet.class);
        when(row.getString("id")).thenReturn("plan-legacy");
        when(row.getString("case_id")).thenReturn("case-legacy");
        when(row.getInt("plan_version")).thenReturn(1);
        when(row.getString("source")).thenReturn("MANUAL");
        when(row.getString("diagnosis_json")).thenReturn("{\"summary\":\"库存核对\",\"confidence\":0.75,\"note\":null}");
        when(row.getString("steps_json")).thenReturn(storedSteps);
        when(row.getString("plan_hash")).thenReturn(oldHash);
        when(row.getString("status")).thenReturn("APPROVED");
        when(row.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.<RowMapper<OpsPlanRecord>>any(), eq("plan-legacy")))
                .thenAnswer(call -> ((RowMapper<OpsPlanRecord>) call.getArgument(1)).mapRow(row, 0));

        OpsCaseRepository repository = new OpsCaseRepository(jdbc, JsonMapper.builder().build());
        OpsPlanRecord plan = repository.latestPlan("case-legacy").orElseThrow();
        assertEquals(oldHash, plan.planHash());
        assertEquals("APPROVED", plan.status());
        assertEquals("库存核对", plan.diagnosis().get("summary"));
        assertTrue(plan.diagnosis().containsKey("note"));
        assertNull(plan.diagnosis().get("note"));
        assertEquals(1, plan.steps().size());
        OpsPlanStep step = plan.steps().get(0);
        assertEquals("repair_seckill_stock", step.toolName());
        assertEquals(9007199254740993L, ((Number) step.arguments().get("voucherId")).longValue());
        assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", step.arguments().get("expectedEvidenceHash"));
        assertEquals("依据已核对证据修复库存", step.purpose());
    }
}
