package com.citycopilot.agent.budget;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class AgentBudgetAdvice {
    @ExceptionHandler(AgentBudgetException.class)
    public ResponseEntity<Map<String, Object>> exhausted(AgentBudgetException error) {
        return ResponseEntity.status(status(error)).body(Map.of("code", error.code(), "message", error.getMessage(),
                "writeOutcomeUnknown", error.writeOutcomeUnknown()));
    }

    static HttpStatus status(AgentBudgetException error) {
        return switch (error.code()) {
            case "agent_overloaded" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "agent_deadline_exceeded" -> HttpStatus.GATEWAY_TIMEOUT;
            case "agent_message_too_large" -> HttpStatus.PAYLOAD_TOO_LARGE;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }
}
