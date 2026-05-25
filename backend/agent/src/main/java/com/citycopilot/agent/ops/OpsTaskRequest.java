package com.citycopilot.agent.ops;

import java.util.Map;

public record OpsTaskRequest(String type, Map<String, Object> payload) {
}
