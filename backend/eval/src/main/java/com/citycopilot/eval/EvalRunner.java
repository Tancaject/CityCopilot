package com.citycopilot.eval;

import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 评测命令行入口：加载 YAML 用例、运行预测器、评分并输出 Markdown 报告。
 *
 * <p>默认使用离线启发式预测器；显式指定 {@code --provider openai-compatible} 才访问模型 API。</p>
 */
public final class EvalRunner {
    /** 未指定在线 API 地址时使用的 OpenAI 兼容端点。 */
    private static final String DEFAULT_PROVIDER_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    /** 未指定在线模型时使用的默认模型。 */
    private static final String DEFAULT_PROVIDER_MODEL = "qwen-plus";

    /** 当前评测运行使用的预测实现。 */
    private final EvalPredictor predictor;

    /** 创建不访问网络的默认评测运行器。 */
    public EvalRunner() {
        this(new HeuristicEvalPredictor());
    }

    /** @param predictor 可替换的预测实现，主要供测试和在线评测使用 */
    EvalRunner(EvalPredictor predictor) {
        this.predictor = predictor;
    }

    /**
     * 解析命令行、执行全部用例并写到标准输出或指定文件。
     *
     * @param args 用例资源、provider、output 和 gate 等选项
     */
    public static void main(String[] args) throws Exception {
        String resourcePath = args.length > 0 && !args[0].startsWith("--")
                ? args[0]
                : "cases/citycopilot-v01.yaml";
        Path output = outputPath(args);
        boolean failOnGate = contains(args, "--fail-on-gate");

        EvalPredictor predictor = predictor(args);
        EvalRunner runner = new EvalRunner(predictor);
        List<EvalResult> results = runner.run(runner.loadCases(resourcePath));
        String report = MarkdownReport.render(results, predictor.metadata());
        if (output == null) {
            System.out.print(report);
        } else {
            Files.createDirectories(output.toAbsolutePath().getParent());
            Files.writeString(output, report);
        }
        if (failOnGate && results.stream().anyMatch(result -> !result.passed())) {
            // CI 使用非零退出码把任一失败用例转为质量门禁失败。
            System.exit(1);
        }
    }

    /**
     * @param resourcePath classpath 下的 YAML 用例路径
     * @return 按文件顺序解析的评测用例
     */
    public List<EvalCase> loadCases(String resourcePath) {
        Yaml yaml = new Yaml();
        try (InputStream input = EvalRunner.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalArgumentException("Eval resource not found: " + resourcePath);
            }
            List<Map<String, Object>> rows = yaml.load(input);
            List<EvalCase> cases = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                cases.add(new EvalCase(
                        String.valueOf(row.get("name")),
                        String.valueOf(resolveVariables(row.get("input"))),
                        String.valueOf(row.get("expect_mode")),
                        strings(row.get("expect_tools")),
                        strings(row.get("expect_keywords")),
                        strings(row.get("expect_no_keywords")),
                        row.get("booking") instanceof Map<?, ?> booking ? stringMap(resolveVariables(booking)) : Map.of()
                ));
            }
            return cases;
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    /**
     * 逐条调用预测器，并分别判断模式、工具包含关系和关键词约束。
     *
     * @param cases 待执行用例
     * @return 与输入顺序一致的评分结果
     */
    public List<EvalResult> run(List<EvalCase> cases) {
        List<EvalResult> results = new ArrayList<>();
        for (EvalCase evalCase : cases) {
            Instant start = Instant.now();
            EvalPrediction prediction = predictor.predict(evalCase);
            String actualMode = prediction.mode() == null ? "" : prediction.mode();
            List<String> predictedTools = prediction.tools() == null ? List.of() : prediction.tools();
            String predictedAnswer = prediction.answer() == null ? "" : prediction.answer();
            boolean modePassed = "ANY".equals(evalCase.expectMode()) || actualMode.equals(evalCase.expectMode());
            boolean toolsPassed = predictedTools.containsAll(evalCase.expectTools());
            boolean keywordsPassed = keywordsPassed(predictedAnswer, evalCase.expectKeywords(), evalCase.expectNoKeywords());
            List<String> structuredFailures = BookingEvalAssertions.check(evalCase, prediction);
            boolean passed = modePassed && toolsPassed && keywordsPassed && structuredFailures.isEmpty();
            results.add(new EvalResult(
                    evalCase.name(),
                    passed,
                    actualMode,
                    evalCase.expectTools(),
                    predictedTools,
                    predictedAnswer,
                    modePassed,
                    toolsPassed,
                    keywordsPassed,
                    tokenOrEstimate(prediction.inputTokens(), evalCase.input() + " " + String.join(" ", predictedTools)),
                    tokenOrEstimate(prediction.outputTokens(), predictedAnswer),
                    Duration.between(start, Instant.now()).toMillis(),
                    structuredFailures
            ));
        }
        return results;
    }

    /** @return 回答是否包含全部必需词且不包含任何禁用词 */
    private boolean keywordsPassed(String answer, List<String> expectedKeywords, List<String> blockedKeywords) {
        String text = answer == null ? "" : answer;
        boolean expectedOk = expectedKeywords == null || expectedKeywords.stream().allMatch(text::contains);
        boolean blockedOk = blockedKeywords == null || blockedKeywords.stream().noneMatch(text::contains);
        return expectedOk && blockedOk;
    }

    /**
     * 在提供方没有 usage 时做轻量估算：ASCII 近似每四字符一个 token，非 ASCII 每字符一个。
     */
    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int asciiLike = 0;
        int nonAscii = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch)) {
                continue;
            }
            if (ch < 128) {
                asciiLike++;
            } else {
                nonAscii++;
            }
        }
        return (int) Math.ceil(asciiLike / 4.0D) + nonAscii;
    }

    /** @return 优先采用非负 provider 统计，否则使用文本估算 */
    private int tokenOrEstimate(Integer providerTokens, String fallbackText) {
        return providerTokens == null ? estimateTokens(fallbackText) : Math.max(0, providerTokens);
    }

    /** @return 将 YAML 列表安全转换为字符串列表，非列表值视为空 */
    private List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    /** @return {@code --output} 后的输出路径；未指定时为 {@code null} */
    private static Path outputPath(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--output".equals(args[i])) {
                return Path.of(args[i + 1]);
            }
        }
        return null;
    }

    /**
     * 根据命令行选择离线或 OpenAI 兼容预测器，并解析在线调用凭证。
     */
    private static EvalPredictor predictor(String[] args) {
        String provider = option(args, "--provider", "offline");
        if ("agent-http".equals(provider)) {
            return new AgentHttpEvalPredictor(option(args, "--agent-url", "http://127.0.0.1:8082"),
                    option(args, "--core-url", "http://127.0.0.1:8081"),
                    envOrDefault("CITYCOPILOT_BOOKING_EVAL_TOKEN", ""),
                    Path.of(option(args, "--evidence-dir", "reports/booking-evidence")));
        }
        if (!"openai-compatible".equals(provider)) {
            return new HeuristicEvalPredictor();
        }
        String apiUrl = option(args, "--api-url", DEFAULT_PROVIDER_URL);
        String model = option(args, "--model", envOrDefault("CITYCOPILOT_AI_MODEL", DEFAULT_PROVIDER_MODEL));
        String keyEnv = option(args, "--api-key-env", "CITYCOPILOT_AI_API_KEY");
        String apiKey = System.getenv(keyEnv);
        if ((apiKey == null || apiKey.isBlank()) && "CITYCOPILOT_AI_API_KEY".equals(keyEnv)) {
            apiKey = System.getenv("DASHSCOPE_API_KEY");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Missing provider API key. Set " + keyEnv + " or pass --api-key-env.");
        }
        return new OpenAiCompatibleEvalPredictor(apiUrl, apiKey, model);
    }

    /** @return 指定命令行选项的值，缺失时返回 fallback */
    private static String option(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) {
                return args[i + 1];
            }
        }
        return fallback;
    }

    /** @return 非空环境变量值，否则返回 fallback */
    private static String envOrDefault(String envName, String fallback) {
        String value = System.getenv(envName);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** @return 参数数组是否包含指定开关 */
    private static boolean contains(String[] args, String value) {
        for (String arg : args) {
            if (value.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    static Object resolveVariables(Object value) {
        if (value instanceof String text) {
            var matcher = java.util.regex.Pattern.compile("\\$\\{([A-Z][A-Z0-9_]+)}").matcher(text);
            StringBuilder resolved = new StringBuilder();
            while (matcher.find()) {
                String replacement = System.getenv(matcher.group(1));
                if (replacement == null) throw new IllegalArgumentException("Missing eval fixture variable: " + matcher.group(1));
                matcher.appendReplacement(resolved, java.util.regex.Matcher.quoteReplacement(replacement));
            }
            return matcher.appendTail(resolved).toString();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), resolveVariables(item))); return result;
        }
        if (value instanceof List<?> list) return list.stream().map(EvalRunner::resolveVariables).toList();
        return value;
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> stringMap(Object value) { return (Map<String, Object>) value; }
}
