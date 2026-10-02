package com.citycopilot.commerce.pricing;

import com.citycopilot.contract.booking.BookingPlanContracts.BatchRequest;
import com.citycopilot.contract.booking.BookingPlanContracts.BatchResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/commerce/v1/booking-options")
@ConditionalOnProperty(prefix = "citycopilot.commerce", name = "api-enabled", havingValue = "true", matchIfMissing = true)
public class BookableOptionsController {
    private final BookableOptionsQueryService queries;

    public BookableOptionsController(BookableOptionsQueryService queries) {
        this.queries = queries;
    }

    @PostMapping("/query")
    public BatchResponse query(@RequestHeader("X-CityCopilot-User-Id") long userId, @RequestBody BatchRequest request) {
        return queries.query(userId, request);
    }
}
