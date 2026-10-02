package com.citycopilot.eval;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/** Reuses the actual Agent SSE route and Core user APIs; optional fixture actions simulate explicit user card approval. */
final class AgentHttpEvalPredictor implements EvalPredictor {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = JsonMapper.builder().build();
    private final String agentUrl, coreUrl, token;
    private final Path evidenceDir;
    AgentHttpEvalPredictor(String agentUrl, String coreUrl, String token, Path evidenceDir) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Set CITYCOPILOT_BOOKING_EVAL_TOKEN to a dedicated test user's session token");
        this.agentUrl = agentUrl; this.coreUrl = coreUrl; this.token = token; this.evidenceDir = evidenceDir;
    }
    @Override public Map<String, String> metadata() {
        return Map.of("provider", "agent-http", "agent_url", agentUrl, "core_url", coreUrl,
                "evidence", evidenceDir.toString(), "usage", "Exact provider counters and fallback outcomes are recorded per case in evidence JSON; missing usage is unavailable.");
    }
    @Override public EvalPrediction predict(EvalCase evalCase) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("source", "agent-http"); evidence.put("case", evalCase.name());
        List<String> tools = new ArrayList<>(); List<Object> calls = new ArrayList<>(), results = new ArrayList<>();
        evidence.put("toolCalls", calls); evidence.put("toolResults", results);
        String mode = "", answer = ""; JsonNode usage = json.createObjectNode(); JsonNode finalBooking = null;
        try {
            evidence.put("beforeBookings", core("GET", "/bookings?page=1", null));
            Map<String, Object> request = new LinkedHashMap<>();
            if (evalCase.booking().get("context") instanceof Map<?, ?> context) context.forEach((key, value) -> request.put(String.valueOf(key), value));
            request.put("message", evalCase.input()); request.put("scene", "user"); request.put("conversationId", "booking-eval-" + UUID.randomUUID());
            String stream = request(agentUrl + "/agent/chat", "POST", request, "text/event-stream");
            String event = ""; StringBuilder data = new StringBuilder();
            for (String line : (stream + "\n\n").split("\\r?\\n", -1)) {
                if (line.startsWith("event:")) event = line.substring(6).trim();
                else if (line.startsWith("data:")) data.append(line.substring(5).trim());
                else if (line.isEmpty() && !data.isEmpty()) {
                    JsonNode body = json.readTree(data.toString()); data.setLength(0);
                    switch (event) {
                        case "traceId" -> { mode = body.path("mode").asText(); evidence.put("traceId", body.path("traceId").asText()); }
                        case "tool" -> {
                            String name = body.path("toolName").asText();
                            if (body.path("phase").asText().equals("call")) { tools.add(name); calls.add(body); }
                            else {
                                JsonNode observation = body.path("observation");
                                if (observation.path("ok").asBoolean()) results.add(Map.of("toolName", name, "data", observation.path("data")));
                                else evidence.put("toolError", observation.path("error"));
                            }
                        }
                        case "final" -> { answer = body.path("message").asText(); evidence.put("durationMs", body.path("durationMs").asLong()); }
                        case "usage" -> { usage = body; evidence.put("usage", usage); }
                        case "error" -> evidence.put("error", body.path("code").asText("agent_error"));
                        case "confirmation" -> evidence.put("legacyConfirmation", body);
                        default -> {}
                    }
                }
            }
            JsonNode settings = json.valueToTree(evalCase.booking());
            if (!settings.path("approve_actions").isEmpty()) {
                if (!"true".equals(System.getenv("CITYCOPILOT_BOOKING_EVAL_ALLOW_APPROVALS"))) throw new IllegalStateException("test_user_approvals_disabled");
                if (!URI.create(coreUrl).getHost().matches("127\\.0\\.0\\.1|localhost|\\[?::1\\]?")) throw new IllegalStateException("booking_write_eval_requires_loopback_fixture");
                Map<String, Object> reviewAssertions = new LinkedHashMap<>();
                if (evalCase.booking().get("assertions") instanceof Map<?, ?> constraints) constraints.forEach((key, value) -> reviewAssertions.put(String.valueOf(key), value));
                reviewAssertions.remove("final_status"); reviewAssertions.remove("frozen_price");
                EvalCase review = new EvalCase(evalCase.name(), evalCase.input(), "ANY", evalCase.expectTools(), List.of(), List.of(), Map.of("assertions", reviewAssertions));
                List<String> reviewFailures = BookingEvalAssertions.check(review, new EvalPrediction(mode, tools, answer, null, null, evidence));
                if (!reviewFailures.isEmpty()) throw new IllegalStateException("review_rejected_before_user_approval:" + String.join(",", reviewFailures));
                JsonNode plan = null;
                for (Object result : results) {
                    JsonNode node = json.valueToTree(result);
                    if (node.path("toolName").asText().equals("search_bookable_options")) plan = node.path("data");
                }
                if (plan == null || plan.path("options").isEmpty()) throw new IllegalStateException("no_authoritative_option_to_approve");
                JsonNode selectedOption = null;
                for (JsonNode option : plan.path("options")) {
                    if (!settings.has("selected_resource_id") || settings.get("selected_resource_id").asText().equals(option.path("resource").path("id").asText())) { selectedOption = option; break; }
                }
                if (selectedOption == null) throw new IllegalStateException("reserved_eval_resource_not_in_options");
                JsonNode selectedQuote = selectedOption.path("quote"); evidence.put("selectedQuote", selectedQuote);
                List<JsonNode> approvals = new ArrayList<>(); evidence.put("userApprovals", approvals);
                for (JsonNode action : settings.path("approve_actions")) {
                    Map<String, Object> proposal = new LinkedHashMap<>(); proposal.put("action", action.asText());
                    if (action.asText().equals("HOLD")) proposal.put("quoteId", selectedQuote.path("quoteId").asText());
                    else if (action.asText().equals("CONFIRM") && finalBooking != null) {
                        proposal.put("bookingId", finalBooking.path("id").asText()); proposal.put("expectedVersion", finalBooking.path("version").asLong());
                    } else throw new IllegalArgumentException("unsupported_fixture_approval");
                    JsonNode approved = approve(proposal); approvals.add(approved);
                    if (!approved.path("state").asText().equals("SUCCEEDED")) throw new IllegalStateException("approval_not_successful_keep_operation:" + approved.path("operationId").asText());
                    finalBooking = core("GET", "/bookings/" + approved.path("result").path("booking").path("id").asText(), null);
                    evidence.put("finalBooking", finalBooking);
                }
            }
            evidence.put("afterBookings", core("GET", "/bookings?page=1", null));
        } catch (Exception error) {
            evidence.put("error", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        } finally {
            if (finalBooking != null) {
                try { evidence.put("cleanup", approve(Map.of("action", "CANCEL", "bookingId", finalBooking.path("id").asText(), "expectedVersion", finalBooking.path("version").asLong(), "reason", "预约评测结束，释放测试资源"))); }
                catch (Exception error) { evidence.put("cleanupError", error.getMessage()); }
            }
            try {
                Files.createDirectories(evidenceDir);
                json.writerWithDefaultPrettyPrinter().writeValue(evidenceDir.resolve(evalCase.name().replaceAll("[^A-Za-z0-9_-]", "_") + ".json").toFile(), evidence);
            } catch (Exception error) { throw new IllegalStateException("Cannot persist booking eval evidence", error); }
        }
        return new EvalPrediction(mode, List.copyOf(tools), answer,
                usage.path("inputTokens").isNumber() ? usage.path("inputTokens").asInt() : null,
                usage.path("outputTokens").isNumber() ? usage.path("outputTokens").asInt() : null, evidence);
    }
    private JsonNode approve(Map<String, Object> proposal) throws Exception {
        JsonNode card = core("POST", "/booking-confirmations", proposal);
        String id = card.path("operationId").asText();
        try { return core("POST", "/booking-confirmations/" + id + "/approve", Map.of("requestHash", card.path("requestHash").asText())); }
        catch (Exception lostResponse) {
            // Query the original operation only. A new proposal or automatic replan must never replace an unknown write.
            try { return core("GET", "/booking-confirmations/" + id, null); }
            catch (Exception unavailable) { throw new IllegalStateException("approval_outcome_unknown_keep_operation:" + id, unavailable); }
        }
    }
    private JsonNode core(String method, String path, Object body) throws Exception {
        JsonNode value = json.readTree(request(coreUrl + path, method, body, "application/json"));
        if (value.has("success")) {
            if (!value.path("success").asBoolean()) throw new IllegalStateException("core_request_rejected:" + value.path("code").asText());
            return value.path("data");
        }
        return value;
    }
    private String request(String url, String method, Object body, String accept) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(70))
                .header("Authorization", token).header("Accept", accept).header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) throw new IllegalStateException("http_" + response.statusCode() + ":" + URI.create(url).getPath());
        return response.body();
    }
}
