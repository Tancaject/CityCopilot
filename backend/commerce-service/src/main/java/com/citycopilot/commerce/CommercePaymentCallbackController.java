package com.citycopilot.commerce;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 渠道入口独立使用 HMAC，不接受用户身份来改变订单归属。 */
@RestController
@ConditionalOnProperty(prefix = "citycopilot.commerce", name = "api-enabled", havingValue = "true", matchIfMissing = true)
public class CommercePaymentCallbackController {
    private final CommercePaymentService payments;

    public CommercePaymentCallbackController(CommercePaymentService payments) { this.payments = payments; }

    @PostMapping("/payment-callbacks/simulated")
    Map<String, String> callback(@RequestBody CommercePaymentService.Callback callback,
            @RequestHeader(value = "X-Payment-Signature", required = false) String signature) {
        return Map.of("result", payments.callback(callback, signature));
    }
}
