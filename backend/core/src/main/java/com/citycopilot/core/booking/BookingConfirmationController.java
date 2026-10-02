package com.citycopilot.core.booking;

import com.citycopilot.contract.booking.BookingConfirmationContracts.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Deliberately absent from the tool catalog: a proposal handle never itself grants execution authority. */
@RestController
@RequestMapping("/booking-confirmations")
public class BookingConfirmationController {
    private final BookingConfirmationService confirmations;
    public BookingConfirmationController(BookingConfirmationService confirmations) { this.confirmations = confirmations; }

    @PostMapping
    public ConfirmationView propose(@RequestBody ProposalRequest request) { return confirmations.propose(request); }
    @GetMapping("/{id}")
    public ConfirmationView status(@PathVariable String id) { return confirmations.status(id); }
    @PostMapping("/{id}/approve")
    public ResponseEntity<ConfirmationView> approve(@PathVariable String id, @RequestBody ApprovalRequest approval, HttpServletRequest request) {
        userInteraction(request);
        ConfirmationView result = confirmations.approve(id, approval.requestHash());
        int status = result.result() != null ? result.result().httpStatus() : result.state().equals("APPROVED") ? 202 : 200;
        return ResponseEntity.status(status).body(result);
    }
    @PostMapping("/{id}/decline")
    public ConfirmationView decline(@PathVariable String id, HttpServletRequest request) {
        userInteraction(request);
        return confirmations.decline(id);
    }
    private void userInteraction(HttpServletRequest request) {
        if (request.getHeader("X-CityCopilot-Service-Token") != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "预约确认必须由用户在确认页面主动提交。");
        }
    }
}
