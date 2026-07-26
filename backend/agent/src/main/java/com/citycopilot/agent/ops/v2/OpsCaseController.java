package com.citycopilot.agent.ops.v2;

import com.citycopilot.agent.security.AgentRequestContextHolder;
import com.citycopilot.agent.security.AgentSecurity;
import com.citycopilot.contract.security.ServiceUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/agent/ops/v2")
public class OpsCaseController {
    private final OpsCaseService caseService;

    public OpsCaseController(OpsCaseService caseService) {
        this.caseService = caseService;
    }

    @GetMapping("/cases")
    public List<OpsCaseRecord> list(@RequestParam(defaultValue = "20") Integer limit) {
        ServiceUser user = requireOps();
        return caseService.list(limit);
    }

    @GetMapping("/cases/{caseId}")
    public Map<String, Object> detail(@PathVariable String caseId) {
        requireOps();
        return caseService.detail(caseId);
    }

    @PostMapping("/cases/{caseId}/plans")
    public Map<String, Object> createPlan(
            @PathVariable String caseId,
            @RequestBody ManualPlanRequest request
    ) {
        ServiceUser user = requireOps();
        return caseService.createManualPlan(
                caseId,
                request.diagnosis(),
                request.steps(),
                actor(user)
        );
    }

    @PostMapping("/approvals/{approvalId}/decision")
    public OpsCaseRecord decide(
            @PathVariable String approvalId,
            @RequestBody ApprovalDecisionRequest request
    ) {
        ServiceUser user = requireOps();
        return caseService.decideApproval(
                approvalId,
                request.planHash(),
                request.approved(),
                actor(user)
        );
    }

    @PostMapping("/cases/{caseId}/cancel")
    public OpsCaseRecord cancel(@PathVariable String caseId) {
        ServiceUser user = requireOps();
        return caseService.cancel(caseId, actor(user));
    }

    @PostMapping("/cases/{caseId}/retry")
    public OpsCaseRecord retry(@PathVariable String caseId) {
        ServiceUser user = requireOps();
        return caseService.retry(caseId, actor(user));
    }

    private ServiceUser requireOps() {
        ServiceUser user = AgentRequestContextHolder.get().user();
        AgentSecurity.requireOpsAccess(user);
        return user;
    }

    private String actor(ServiceUser user) {
        return user.id() == null ? "ops-user" : String.valueOf(user.id());
    }

    public record ManualPlanRequest(
            Map<String, Object> diagnosis,
            List<OpsPlanStep> steps
    ) {
    }

    public record ApprovalDecisionRequest(
            String planHash,
            boolean approved
    ) {
    }
}
