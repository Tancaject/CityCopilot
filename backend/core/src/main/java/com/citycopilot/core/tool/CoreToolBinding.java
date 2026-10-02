package com.citycopilot.core.tool;

import com.citycopilot.contract.tool.ToolDefinition;

import java.lang.reflect.Method;

/**
 * 一个可执行 Core 工具的运行时绑定。
 *
 * @param definition 对外暴露的工具合同与参数 Schema
 * @param bean 实际承载方法的 Spring Bean
 * @param method 可在代理对象上反射调用的方法
 */
record CoreToolBinding(ToolDefinition definition, Object bean, Method method) {
}
