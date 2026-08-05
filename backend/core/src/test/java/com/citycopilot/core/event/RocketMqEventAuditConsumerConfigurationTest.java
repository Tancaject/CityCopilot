package com.citycopilot.core.event;

import com.citycopilot.core.config.RocketMqProperties;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RocketMqEventAuditConsumerConfigurationTest {

    @Test
    void configuresTlsAndCredentialsTogether() {
        RocketMqProperties properties = new RocketMqProperties();
        properties.setEndpoints("mq.example.internal:8081");
        properties.setSslEnabled(true);
        properties.setAccessKey("access");
        properties.setSecretKey("secret");

        ClientConfiguration configuration = RocketMqEventAuditConsumer.clientConfiguration(properties);

        assertThat(configuration.isSslEnabled()).isTrue();
        assertThat(configuration.getCredentialsProvider()).isPresent();
    }

    @Test
    void rejectsPartialCredentials() {
        RocketMqProperties properties = new RocketMqProperties();
        properties.setSecretKey("secret");

        assertThatThrownBy(() -> RocketMqEventAuditConsumer.clientConfiguration(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configured together");
    }
}
