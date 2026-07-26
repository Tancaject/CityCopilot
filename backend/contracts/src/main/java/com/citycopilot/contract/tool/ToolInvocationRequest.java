package com.citycopilot.contract.tool;

import java.util.LinkedHashMap;
import java.util.Map;

public record ToolInvocationRequest(Map<String, Object> arguments, String requestId) {
    public ToolInvocationRequest {
        arguments = arguments == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(arguments));
    }
}
