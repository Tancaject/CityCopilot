package com.citycopilot.agent.ops;

import com.citycopilot.agent.security.AgentRequestContextHolder;
import com.citycopilot.agent.security.AgentSecurity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 旧版运维任务入口。
 *
 * <p>所有端点先校验运维权限，再把任务写入 Redis Stream；新流程逐步迁移到可审批、可追踪的 ops case v2。</p>
 */
@RestController
@RequestMapping("/agent/ops")
public class OpsAgentController {
    /** 旧版运维队列与执行服务。 */
    private final OpsAgentService opsAgentService;

    public OpsAgentController(OpsAgentService opsAgentService) {
        this.opsAgentService = opsAgentService;
    }

    /** 提交地理坐标校准规划任务。 */
    @PostMapping("/tasks/geo-calibration")
    public Map<String, Object> geoCalibration(@RequestBody(required = false) Map<String, Object> payload) {
        requireOpsAccess();
        return opsAgentService.enqueue("geo_calibration", payload);
    }

    /** 提交订单异常只读扫描任务。 */
    @PostMapping("/tasks/order-anomalies")
    public Map<String, Object> orderAnomalies(@RequestBody(required = false) Map<String, Object> payload) {
        requireOpsAccess();
        return opsAgentService.enqueue("order_anomaly_scan", payload);
    }

    /** 提交每日运维汇总任务。 */
    @PostMapping("/tasks/daily-report")
    public Map<String, Object> dailyReport(@RequestBody(required = false) Map<String, Object> payload) {
        requireOpsAccess();
        return opsAgentService.enqueue("daily_report", payload);
    }

    /** @return Redis 中最近完成的旧版任务结果 */
    @GetMapping("/results")
    public Map<String, Object> recentResults(@RequestParam(defaultValue = "20") Integer limit) {
        requireOpsAccess();
        return opsAgentService.recentResults(limit);
    }

    private void requireOpsAccess() {
        AgentSecurity.requireOpsAccess(AgentRequestContextHolder.get().user());
    }
}
