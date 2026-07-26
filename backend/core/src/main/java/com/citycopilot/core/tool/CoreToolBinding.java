package com.citycopilot.core.tool;

import com.citycopilot.contract.tool.ToolDefinition;

import java.lang.reflect.Method;

record CoreToolBinding(ToolDefinition definition, Object bean, Method method) {
}
