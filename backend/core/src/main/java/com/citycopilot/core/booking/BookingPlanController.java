package com.citycopilot.core.booking;

import com.citycopilot.contract.booking.BookingPlanContracts.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class BookingPlanController {
    private final BookingPlanService plans;
    public BookingPlanController(BookingPlanService plans) { this.plans = plans; }

    @PostMapping("/booking-plans")
    public PlanResponse plan(@RequestBody PlanRequest request) { return plans.plan(request); }
}
