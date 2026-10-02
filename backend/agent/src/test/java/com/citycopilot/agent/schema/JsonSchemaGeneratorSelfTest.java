package com.citycopilot.agent.schema;

import com.citycopilot.agent.tool.AgentToolRegistry;
import com.citycopilot.agent.tool.ToolMetadata;
import com.citycopilot.contract.tool.AgentTool;

import java.util.List;
import java.util.Map;

public final class JsonSchemaGeneratorSelfTest {

    @org.junit.jupiter.api.Test
    void remoteStructuredSchemaSurvivesMetadataConversion() {
        var requestSchema = Map.<String, Object>of("type", "object", "properties",
                Map.of("partySize", Map.of("type", "integer"), "date", Map.of("type", "string", "format", "date")));
        var definition = new com.citycopilot.contract.tool.ToolDefinition("search_bookable_options", "booking", List.of(), 60, true,
                List.of(new com.citycopilot.contract.tool.ToolParameterDefinition("request", "com.citycopilot.contract.booking.BookingPlanContracts$PlanRequest", false, requestSchema)));
        var schema = JsonSchemaGenerator.parametersSchema(ToolMetadata.fromDefinition(definition));
        assertEquals(requestSchema, ((Map<?, ?>) schema.get("properties")).get("request"), "structured request");
    }

    public static void main(String[] args) {
        ToolMetadata tool = AgentToolRegistry.fromBeans(List.of(new ShopTools()))
                .require("search_shops_by_type");

        Map<String, Object> schema = JsonSchemaGenerator.parametersSchema(tool);
        Map<?, ?> properties = (Map<?, ?>) schema.get("properties");
        Map<?, ?> typeId = (Map<?, ?>) properties.get("typeId");
        Map<?, ?> keyword = (Map<?, ?>) properties.get("keyword");

        assertEquals("object", schema.get("type"), "schema type");
        assertEquals("integer", typeId.get("type"), "integer parameter");
        assertEquals("string", keyword.get("type"), "string parameter");
        assertEquals(List.of("typeId", "keyword"), schema.get("required"), "required parameters");
    }

    static final class ShopTools {
        @AgentTool(name = "search_shops_by_type", description = "Search shops by type")
        public String search(Integer typeId, String keyword) {
            return typeId + keyword;
        }
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + " expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
