package com.citycopilot.agent.tool;

import com.citycopilot.agent.config.AgentModuleConfiguration;
import com.citycopilot.core.agent.AgentTool;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentToolRegistrySpringTest {

    @Test
    void scansSpringBeansForAgentTools() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(AgentModuleConfiguration.class, DemoConfig.class);
            context.refresh();

            AgentToolRegistry registry = context.getBean(AgentToolRegistry.class);

            assertEquals(1, registry.tools().size());
            assertEquals("echo_city", registry.require("echo_city").name());
        }
    }

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

    @Configuration
    static class DemoConfig {
        @Bean
        DemoToolBean demoToolBean() {
            return new DemoToolBean();
        }
    }

    static class DemoToolBean {
        @AgentTool(name = "echo_city", description = "Echo city")
        public String echo(String city) {
            return city;
        }
    }

    interface InterfaceTool {
        @AgentTool(name = "echo_proxy_city", description = "Echo proxied city")
        String echo(String city);
    }
}
