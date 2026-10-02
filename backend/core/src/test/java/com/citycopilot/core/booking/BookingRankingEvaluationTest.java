package com.citycopilot.core.booking;

import tools.jackson.databind.json.JsonMapper;

import com.citycopilot.core.dto.ShopDiscoveryItemDTO;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Fixed synthetic judgements check ranking mechanics, not real user conversion. */
class BookingRankingEvaluationTest {
    record Case(String name, List<String> preferences, List<ShopDiscoveryItemDTO> candidates,
                Map<String, BookingFeedbackService.ShopFeature> features, Map<Long, Integer> grades) {}

    @Test
    void evaluateFixedCandidatesAndExportNdcgAtTen() throws Exception {
        var cases = new ArrayList<Case>();
        for (String scenario : List.of("餐厅", "运动馆")) {
            var nearby = candidates(scenario);
            nearby.get(9).setDistance(50d);
            cases.add(new Case(scenario + "-冷启动距离", List.of(), nearby, Map.of(), Map.of(10L, 3)));
            var explicit = candidates(scenario);
            explicit.get(8).setTag("安静");
            cases.add(new Case(scenario + "-明确偏好", List.of("安静"), explicit, Map.of(), Map.of(9L, 3)));
            var returning = candidates(scenario);
            cases.add(new Case(scenario + "-近期履约和负反馈", List.of(), returning,
                    Map.of("10", feature("10", 2, false), "1", feature("1", 0, true)), Map.of(10L, 3, 2L, 1)));
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (Case sample : cases) {
            List<Long> baseline = sample.candidates().stream().map(ShopDiscoveryItemDTO::getId).toList();
            long started = System.nanoTime();
            var ranked = sample.candidates().stream().sorted(BookingPlanService.ranking(sample.preferences(), sample.features()))
                    .map(ShopDiscoveryItemDTO::getId).toList();
            long nanos = System.nanoTime() - started;
            assertEquals(new HashSet<>(baseline), new HashSet<>(ranked), "Ranking must not add or drop candidates");
            double before = ndcg(baseline, sample.grades()), after = ndcg(ranked, sample.grades());
            assertTrue(after >= before, sample.name());
            assertEquals(1d, after, 0.000001, sample.name());
            results.add(Map.of("query", sample.name(), "baselineOrder", baseline, "rankingOrder", ranked,
                    "ndcgAt10Before", before, "ndcgAt10After", after, "rankingNanos", nanos));
        }
        var output = Path.of("target/booking-ranking-evaluation.json");
        Files.createDirectories(output.getParent());
        JsonMapper.builderWithJackson2Defaults().build().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), Map.of(
                "dataset", "6 fixed synthetic engineering cases, 10 candidates each; no online traffic",
                "rankingVersion", BookingPlanService.RANKING_VERSION,
                "scope", "Comparator only; real capacity and budget filtering are tested in BookingLifecycleIT",
                "results", results));
    }

    private List<ShopDiscoveryItemDTO> candidates(String scenario) {
        var result = new ArrayList<ShopDiscoveryItemDTO>();
        for (long id = 1; id <= 10; id++) result.add(new ShopDiscoveryItemDTO().setId(id).setName(scenario + id)
                .setScore(40).setDistance(1000d).setSource("local").setOrderable(true));
        return result;
    }

    private BookingFeedbackService.ShopFeature feature(String id, long used, boolean negative) {
        return new BookingFeedbackService.ShopFeature(id, 0, 0, 0, used, used, 0, 0, negative, null);
    }

    private double ndcg(List<Long> order, Map<Long, Integer> grades) {
        var ideal = new ArrayList<>(order);
        ideal.sort(Comparator.comparingInt(id -> -grades.getOrDefault(id, 0)));
        double best = dcg(ideal, grades);
        return best == 0 ? 1 : dcg(order, grades) / best;
    }

    private double dcg(List<Long> order, Map<Long, Integer> grades) {
        double value = 0;
        for (int index = 0; index < Math.min(10, order.size()); index++) {
            value += (Math.pow(2, grades.getOrDefault(order.get(index), 0)) - 1) / (Math.log(index + 2) / Math.log(2));
        }
        return value;
    }
}
