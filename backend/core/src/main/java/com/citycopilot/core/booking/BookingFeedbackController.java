package com.citycopilot.core.booking;

import com.citycopilot.core.utils.UserHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/booking-feedback")
public class BookingFeedbackController {
    private final BookingFeedbackService feedback;
    public BookingFeedbackController(BookingFeedbackService feedback) { this.feedback = feedback; }

    @PostMapping
    public BookingFeedbackService.FeedbackReceipt record(@RequestBody BookingFeedbackService.FeedbackRequest request) {
        return feedback.recordFeedback(userId(), request);
    }

    @DeleteMapping("/negative/{shopId}")
    public ResponseEntity<Void> clear(@PathVariable String shopId) {
        feedback.clearNegativeFeedback(userId(), shopId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/rules")
    public BookingFeedbackService.EffectiveRules rules() { return feedback.rules(); }

    @ExceptionHandler(BookingFeedbackService.Rejected.class)
    public ResponseEntity<Failure> rejected(BookingFeedbackService.Rejected error) {
        return ResponseEntity.status(error.status).body(new Failure(error.code, error.getMessage()));
    }

    public record Failure(String code, String message) {}
    private static long userId() {
        var user = UserHolder.getUser();
        if (user == null || user.getId() == null) throw new BookingFeedbackService.Rejected("BOOKING_AUTH_REQUIRED", 401, "请先登录再管理预约反馈");
        return user.getId();
    }
}
