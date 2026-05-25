package com.citycopilot.agent.tool;

public record ToolParameter(String name, Class<?> javaType, boolean required) {
}
