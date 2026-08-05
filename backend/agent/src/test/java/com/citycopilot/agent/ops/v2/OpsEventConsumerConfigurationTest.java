package com.citycopilot.agent.ops.v2;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpsEventConsumerConfigurationTest {

    @Test
    void configuresTlsAndCredentialsTogether() {
        AgentRocketMqProperties properties = new AgentRocketMqProperties();
        properties.setEndpoints("mq.example.internal:8081");
        properties.setSslEnabled(true);
        properties.setAccessKey("access");
        properties.setSecretKey("secret");

        ClientConfiguration configuration = OpsEventConsumer.clientConfiguration(properties);

        assertThat(configuration.isSslEnabled()).isTrue();
        assertThat(configuration.getCredentialsProvider()).isPresent();
    }

    @Test
    void rejectsPartialCredentials() {
        AgentRocketMqProperties properties = new AgentRocketMqProperties();
        properties.setAccessKey("access");

        assertThatThrownBy(() -> OpsEventConsumer.clientConfiguration(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configured together");
    }
}
