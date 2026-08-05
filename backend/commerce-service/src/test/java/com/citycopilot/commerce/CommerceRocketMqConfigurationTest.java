package com.citycopilot.commerce;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommerceRocketMqConfigurationTest {

    @Test
    void configuresTlsAndCredentialsTogether() {
        ClientConfiguration configuration = CommerceOutboxRelay.clientConfiguration(
                "mq.example.internal:8081",
                true,
                "access",
                "secret"
        );

        assertThat(configuration.isSslEnabled()).isTrue();
        assertThat(configuration.getCredentialsProvider()).isPresent();
    }

    @Test
    void rejectsPartialCredentials() {
        assertThatThrownBy(() -> CommerceOutboxRelay.clientConfiguration(
                "mq.example.internal:8081",
                false,
                "access",
                ""
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configured together");
    }
}
