package com.citycopilot.agent.tool;

import com.citycopilot.contract.tool.AgentTool;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentToolRegistrySpringTest {

    @Test
    void scansAgentToolAnnotationsFromJdkProxyInterfaces() {
        InterfaceTool proxy = (InterfaceTool) Proxy.newProxyInstance(
                InterfaceTool.class.getClassLoader(),
                new Class[]{InterfaceTool.class},
                (target, method, args) -> args == null ? null : args[0]
        );

        AgentToolRegistry registry = AgentToolRegistry.fromBeans(List.of(proxy));

        assertEquals(1, registry.tools().size());
        assertEquals("echo_proxy_city", registry.require("echo_proxy_city").name());
    }

    interface InterfaceTool {
        @AgentTool(name = "echo_proxy_city", description = "Echo proxied city")
        String echo(String city);
    }
}
