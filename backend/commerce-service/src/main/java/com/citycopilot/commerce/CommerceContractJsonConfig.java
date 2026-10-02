package com.citycopilot.commerce;

import com.citycopilot.contract.commerce.CommerceContracts.DeadLetterReplayResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 重放的三个历史响应分支省略未出现的字段，不改变其他响应中的显式 null。 */
@Configuration(proxyBeanMethods = false)
class CommerceContractJsonConfig {
    @Bean
    JsonMapperBuilderCustomizer commerceReplayWire() {
        return builder -> builder.addMixIn(DeadLetterReplayResult.class, SparseReplay.class);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    abstract static class SparseReplay {}
}
