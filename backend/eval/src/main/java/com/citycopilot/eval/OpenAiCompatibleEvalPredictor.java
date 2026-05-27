package com.citycopilot.eval;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class OpenAiCompatibleEvalPredictor implements EvalPredictor {
    private static final List<String> TOOL_NAMES = List.of(
            "search_nearby_shops",
            "search_shops_by_type",
            "get_shop_detail",
            "list_shop_types",
            "list_vouchers_by_shop",
            "place_seckill_order",
            "get_my_orders",
            "cancel_order",
            "pay_order",
            "refund_order",
            "list_hot_blogs",
            "get_blog_detail",
            "like_blog",
            "list_blog_likes",
            "get_follow_feed",
            "set_follow_user",
            "check_follow_user",
            "list_common_follows",
            "geocode_address",
            "search_geo_poi",
            "get_geo_provider_status",
            "start_batch_geocode",
            "list_geo_batch_jobs",
            "retry_geo_batch_failed_items",
            "query_audit_logs",
            "get_audit_retention_status",
            "ops_summary"
    );

    private final HttpClient httpClient;
    private final String apiUrl;
    private final String apiKey;
    private final String model;

    OpenAiCompatibleEvalPredictor(String apiUrl, String apiKey, String model) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public EvalPrediction predict(EvalCase evalCase) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(JSONUtil.toJsonStr(requestBody(evalCase))))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                return providerError(evalCase, "http_" + response.statusCode(), null, null);
            }
            JSONObject body = JSONUtil.parseObj(response.body());
            JSONObject usage = body.getJSONObject("usage");
            JSONObject prediction = JSONUtil.parseObj(extractJson(contentOf(body)));
            return new EvalPrediction(
                    normalizeMode(prediction.getStr("mode", "REACT")),
                    stringList(prediction.get("tools")),
                    prediction.getStr("answer", ""),
                    integerValue(usage, "prompt_tokens"),
                    integerValue(usage, "completion_tokens")
            );
        } catch (Exception error) {
            return providerError(evalCase, "provider_error", null, null);
        }
    }

    @Override
    public Map<String, String> metadata() {
        return Map.of(
                "provider", "openai-compatible",
                "model", model,
                "api_url", apiUrl
        );
    }

    private Map<String, Object> requestBody(EvalCase evalCase) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", 0);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt()),
                Map.of("role", "user", "content", evalCase.input() == null ? "" : evalCase.input())
        ));
        return body;
    }

    private String systemPrompt() {
        return """
                You are evaluating CityCopilot routing.
                Return exactly one JSON object and no markdown.
                Schema: {"mode":"REACT|PLAN_EXECUTE","tools":["tool_name"],"answer":"short answer containing key intent words"}.
                Use PLAN_EXECUTE only for multi-step actions or write operations. Use REACT for single read-only lookups.
                Tool names:
                """ + JSONUtil.toJsonStr(TOOL_NAMES);
    }

    private String normalizeMode(String mode) {
        String normalized = mode == null ? "" : mode.toUpperCase(Locale.ROOT);
        return "PLAN_EXECUTE".equals(normalized) ? "PLAN_EXECUTE" : "REACT";
    }

    private Object contentOf(JSONObject body) {
        JSONArray choices = body.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            return "";
        }
        Object first = choices.get(0);
        JSONObject choice = first instanceof JSONObject object ? object : JSONUtil.parseObj(first);
        JSONObject message = choice.getJSONObject("message");
        return message == null ? "" : message.get("content");
    }

    private String extractJson(Object content) {
        String text = content == null ? "" : String.valueOf(content);
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return "{}";
    }

    private List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        JSONArray array = value instanceof JSONArray jsonArray ? jsonArray : JSONUtil.parseArray(value);
        List<String> out = new ArrayList<>();
        for (Object item : array) {
            out.add(String.valueOf(item));
        }
        return out;
    }

    private Integer integerValue(JSONObject object, String key) {
        return object == null ? null : object.getInt(key);
    }

    private EvalPrediction providerError(EvalCase evalCase, String code, Integer inputTokens, Integer outputTokens) {
        return new EvalPrediction(
                "REACT",
                List.of(),
                "provider_eval_failed:" + code + " | " + (evalCase.input() == null ? "" : evalCase.input()),
                inputTokens,
                outputTokens
        );
    }
}
