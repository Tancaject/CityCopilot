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

public final class EvalRunner {
    private static final String DEFAULT_PROVIDER_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String DEFAULT_PROVIDER_MODEL = "qwen-plus";

    private final EvalPredictor predictor;

    public EvalRunner() {
        this(new HeuristicEvalPredictor());
    }

    EvalRunner(EvalPredictor predictor) {
        this.predictor = predictor;
    }

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
            System.exit(1);
        }
    }

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
                        String.valueOf(row.get("input")),
                        String.valueOf(row.get("expect_mode")),
                        strings(row.get("expect_tools")),
                        strings(row.get("expect_keywords")),
                        strings(row.get("expect_no_keywords"))
                ));
            }
            return cases;
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    public List<EvalResult> run(List<EvalCase> cases) {
        List<EvalResult> results = new ArrayList<>();
        for (EvalCase evalCase : cases) {
            Instant start = Instant.now();
            EvalPrediction prediction = predictor.predict(evalCase);
            String actualMode = prediction.mode() == null ? "" : prediction.mode();
            List<String> predictedTools = prediction.tools() == null ? List.of() : prediction.tools();
            String predictedAnswer = prediction.answer() == null ? "" : prediction.answer();
            boolean modePassed = actualMode.equals(evalCase.expectMode());
            boolean toolsPassed = predictedTools.containsAll(evalCase.expectTools());
            boolean keywordsPassed = keywordsPassed(predictedAnswer, evalCase.expectKeywords(), evalCase.expectNoKeywords());
            boolean passed = modePassed && toolsPassed && keywordsPassed;
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
                    Duration.between(start, Instant.now()).toMillis()
            ));
        }
        return results;
    }

    private boolean keywordsPassed(String answer, List<String> expectedKeywords, List<String> blockedKeywords) {
        String text = answer == null ? "" : answer;
        boolean expectedOk = expectedKeywords == null || expectedKeywords.stream().allMatch(text::contains);
        boolean blockedOk = blockedKeywords == null || blockedKeywords.stream().noneMatch(text::contains);
        return expectedOk && blockedOk;
    }

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

    private int tokenOrEstimate(Integer providerTokens, String fallbackText) {
        return providerTokens == null ? estimateTokens(fallbackText) : Math.max(0, providerTokens);
    }

    private List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private static Path outputPath(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--output".equals(args[i])) {
                return Path.of(args[i + 1]);
            }
        }
        return null;
    }

    private static EvalPredictor predictor(String[] args) {
        String provider = option(args, "--provider", "offline");
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

    private static String option(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) {
                return args[i + 1];
            }
        }
        return fallback;
    }

    private static String envOrDefault(String envName, String fallback) {
        String value = System.getenv(envName);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean contains(String[] args, String value) {
        for (String arg : args) {
            if (value.equals(arg)) {
                return true;
            }
        }
        return false;
    }
}
