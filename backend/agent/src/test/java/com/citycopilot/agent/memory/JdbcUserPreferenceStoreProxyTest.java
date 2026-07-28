package com.citycopilot.agent.memory;

import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

class JdbcUserPreferenceStoreProxyTest {

    @Test
    void supportsSpringClassBasedRepositoryProxy() {
        ProxyFactory proxyFactory = new ProxyFactory(
                new JdbcUserPreferenceStore(mock(JdbcTemplate.class))
        );
        proxyFactory.setProxyTargetClass(true);

        assertInstanceOf(UserPreferenceStore.class, proxyFactory.getProxy());
    }
}
