package com.citycopilot.agent.ops;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/agent/ops")
public class OpsAgentController {
    private final OpsAgentService opsAgentService;

    public OpsAgentController(OpsAgentService opsAgentService) {
        this.opsAgentService = opsAgentService;
    }

    @PostMapping("/tasks/geo-calibration")
    public Map<String, Object> geoCalibration(@RequestBody(required = false) Map<String, Object> payload) {
        return opsAgentService.enqueue("geo_calibration", payload);
    }

    @PostMapping("/tasks/order-anomalies")
    public Map<String, Object> orderAnomalies(@RequestBody(required = false) Map<String, Object> payload) {
        return opsAgentService.enqueue("order_anomaly_scan", payload);
    }

    @PostMapping("/tasks/daily-report")
    public Map<String, Object> dailyReport(@RequestBody(required = false) Map<String, Object> payload) {
        return opsAgentService.enqueue("daily_report", payload);
    }

    @PostMapping("/poll-once")
    public Map<String, Object> pollOnce() {
        return opsAgentService.pollOnce();
    }

    @GetMapping("/results")
    public Map<String, Object> recentResults(@RequestParam(defaultValue = "20") Integer limit) {
        return opsAgentService.recentResults(limit);
    }
}
