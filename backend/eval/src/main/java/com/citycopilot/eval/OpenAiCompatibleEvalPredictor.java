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

/**
 * 调用 OpenAI 兼容 Chat Completions API 的在线评测预测器。
 *
 * <p>模型只输出模式、工具名和短回答；解析或网络失败会变成可评分的 provider_error 结果，
 * 不会中断整批用例。</p>
 */
final class OpenAiCompatibleEvalPredictor implements EvalPredictor {
    /** 在线评测系统提示允许模型选择的工具名白名单。 */
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

    /** 发送带连接超时的 JDK HTTP 请求。 */
    private final HttpClient httpClient;
    /** OpenAI 兼容 chat/completions 地址。 */
    private final String apiUrl;
    /** 仅写入 Authorization 头的 API 密钥。 */
    private final String apiKey;
    /** 提供方模型名。 */
    private final String model;

    /**
     * @param apiUrl Chat Completions API 地址
     * @param apiKey Bearer API 密钥
     * @param model 提供方模型名
     */
    OpenAiCompatibleEvalPredictor(String apiUrl, String apiKey, String model) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    /** 调用在线模型并把 JSON 内容转换为统一预测结果。 */
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
                // HTTP 错误进入普通评分结果，保证一条外部失败不会丢失整批报告。
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

    /** @return 报告需要展示的 provider、模型和 API 地址 */
    @Override
    public Map<String, String> metadata() {
        return Map.of(
                "provider", "openai-compatible",
                "model", model,
                "api_url", apiUrl
        );
    }

    /** @return 固定 temperature 的 Chat Completions 请求体 */
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

    /** @return 约束模式、工具白名单和纯 JSON 输出的系统提示 */
    private String systemPrompt() {
        return """
                You are evaluating CityCopilot routing.
                Return exactly one JSON object and no markdown.
                Schema: {"mode":"REACT|PLAN_EXECUTE","tools":["tool_name"],"answer":"short answer containing key intent words"}.
                Use PLAN_EXECUTE only for multi-step actions or write operations. Use REACT for single read-only lookups.
                Tool names:
                """ + JSONUtil.toJsonStr(TOOL_NAMES);
    }

    /** @return 仅保留 PLAN_EXECUTE，其余未知值安全归一为 REACT */
    private String normalizeMode(String mode) {
        String normalized = mode == null ? "" : mode.toUpperCase(Locale.ROOT);
        return "PLAN_EXECUTE".equals(normalized) ? "PLAN_EXECUTE" : "REACT";
    }

    /** @return 第一条 choice 的 message.content，缺失时返回空文本 */
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

    /** @return 从可能带额外文本或 Markdown 的内容中截取首尾 JSON 对象 */
    private String extractJson(Object content) {
        String text = content == null ? "" : String.valueOf(content);
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return "{}";
    }

    /** @return 将 JSON 数组值转换为工具名列表 */
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

    /** @return usage 中的整数统计；usage 缺失时返回 {@code null} */
    private Integer integerValue(JSONObject object, String key) {
        return object == null ? null : object.getInt(key);
    }

    /**
     * 把提供方失败转换为可继续参与常规断言的确定格式，避免中断整批评测。
     */
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
