package com.citycopilot.eval;

import com.citycopilot.agent.router.AgentRouter;
import com.citycopilot.agent.router.Mode;
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
    private final AgentRouter router = new AgentRouter();

    public static void main(String[] args) throws Exception {
        String resourcePath = args.length > 0 && !args[0].startsWith("--")
                ? args[0]
                : "cases/citycopilot-v01.yaml";
        Path output = outputPath(args);
        boolean failOnGate = contains(args, "--fail-on-gate");

        EvalRunner runner = new EvalRunner();
        List<EvalResult> results = runner.run(runner.loadCases(resourcePath));
        String report = MarkdownReport.render(results);
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
                        strings(row.get("expect_keywords"))
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
            Mode mode = router.classify(evalCase.input());
            List<String> predictedTools = predictTools(evalCase.input(), mode);
            boolean passed = mode.name().equals(evalCase.expectMode())
                    && predictedTools.containsAll(evalCase.expectTools());
            results.add(new EvalResult(
                    evalCase.name(),
                    passed,
                    mode.name(),
                    evalCase.expectTools(),
                    predictedTools,
                    Duration.between(start, Instant.now()).toMillis()
            ));
        }
        return results;
    }

    private List<String> predictTools(String input, Mode mode) {
        String text = input == null ? "" : input;
        List<String> tools = new ArrayList<>();
        if (mode == Mode.PLAN_EXECUTE) {
            if (text.contains("下单")) {
                tools.add("search_nearby_shops");
                tools.add("list_vouchers_by_shop");
                tools.add("place_seckill_order");
            }
            if (text.contains("支付")) {
                tools.add("pay_order");
            }
            if (text.contains("退款")) {
                tools.add("refund_order");
            }
            if (text.contains("取消")) {
                tools.add("cancel_order");
            }
            return tools;
        }
        if (text.contains("订单")) {
            tools.add("get_my_orders");
        } else if (text.contains("优惠券") || text.contains("券")) {
            tools.add("list_vouchers_by_shop");
        } else if (text.contains("地理") || text.contains("高德")) {
            tools.add("get_geo_provider_status");
        } else if (text.contains("审计")) {
            tools.add("query_audit_logs");
        } else if (text.contains("运营")) {
            tools.add("ops_summary");
        } else {
            tools.add("search_nearby_shops");
        }
        return tools;
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

    private static boolean contains(String[] args, String value) {
        for (String arg : args) {
            if (value.equals(arg)) {
                return true;
            }
        }
        return false;
    }
}
