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

/**
 * Ops Case v2 的人工查询、规划、审批和控制接口。
 *
 * <p>每个端点都要求运维权限；操作者身份写入状态转移日志，审批还必须回传展示过的 planHash。</p>
 */
@RestController
@RequestMapping("/agent/ops/v2")
public class OpsCaseController {
    /** 编排 case 状态机和计划审批的应用服务。 */
    private final OpsCaseService caseService;

    public OpsCaseController(OpsCaseService caseService) {
        this.caseService = caseService;
    }

    /** @return 最近更新的运维 case 列表 */
    @GetMapping("/cases")
    public List<OpsCaseRecord> list(@RequestParam(defaultValue = "20") Integer limit) {
        ServiceUser user = requireOps();
        return caseService.list(limit);
    }

    /**
     * 查询 case、证据、最新计划、执行记录和状态转移时间线。
     */
    @GetMapping("/cases/{caseId}")
    public Map<String, Object> detail(@PathVariable String caseId) {
        requireOps();
        return caseService.detail(caseId);
    }

    /**
     * 为处于人工复核状态的 case 提交候选计划。
     *
     * <p>服务层仍会重新校验证据绑定、工具白名单并生成审批哈希。</p>
     */
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

    /**
     * 对尚未过期的审批作一次性决定。
     *
     * @param approvalId 审批记录标识
     * @param request 包含当时展示的计划哈希和批准/拒绝决定
     */
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

    /** 请求取消 case；执行中的任务采用协作式取消，不强行中断已发出的调用。 */
    @PostMapping("/cases/{caseId}/cancel")
    public OpsCaseRecord cancel(@PathVariable String caseId) {
        ServiceUser user = requireOps();
        return caseService.cancel(caseId, actor(user));
    }

    /** 将人工复核或失败 case 重新放入受控重试流程。 */
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

    /**
     * 人工提交的诊断与受限步骤。
     *
     * @param diagnosis 结构化诊断说明
     * @param steps 候选修复步骤
     */
    public record ManualPlanRequest(
            Map<String, Object> diagnosis,
            List<OpsPlanStep> steps
    ) {
    }

    /**
     * 审批决定请求。
     *
     * @param planHash 审批人查看并决定的计划哈希
     * @param approved 是否批准执行
     */
    public record ApprovalDecisionRequest(
            String planHash,
            boolean approved
    ) {
    }
}
