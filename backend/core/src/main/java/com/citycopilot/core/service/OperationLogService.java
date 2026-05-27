package com.citycopilot.core.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.citycopilot.core.agent.AgentTool;
import com.citycopilot.core.config.AuditProperties;
import com.citycopilot.core.dto.UserDTO;
import com.citycopilot.core.entity.OperationLog;
import com.citycopilot.core.mapper.OperationLogMapper;
import com.citycopilot.core.security.PermissionCodes;
import com.citycopilot.core.utils.SystemConstants;
import com.citycopilot.core.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class OperationLogService extends ServiceImpl<OperationLogMapper, OperationLog> {

    @Resource
    private AuditProperties auditProperties;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            String module,
            String action,
            String targetType,
            Long targetId,
            String targetKey,
            Object beforeData,
            Object afterData,
            boolean success,
            String errorMessage
    ) {
        try {
            UserDTO actor = UserHolder.getUser();
            OperationLog logEntry = new OperationLog()
                    .setActorId(actor == null ? null : actor.getId())
                    .setActorName(actor == null ? null : actor.getNickName())
                    .setActorRole(actor == null ? null : actor.getRole())
                    .setModule(module)
                    .setAction(action)
                    .setTargetType(targetType)
                    .setTargetId(targetId)
                    .setTargetKey(targetKey)
                    .setBeforeData(toJson(beforeData))
                    .setAfterData(toJson(afterData))
                    .setSuccess(success)
                    .setErrorMessage(errorMessage)
                    .setCreateTime(LocalDateTime.now());
            save(logEntry);
        } catch (Exception error) {
            log.warn("Operation audit write failed: {}", error.getMessage());
        }
    }

    @AgentTool(
            name = "query_audit_logs",
            description = "Query operation audit logs",
            requiredPermissions = {PermissionCodes.AUDIT_READ}
    )
    public Page<OperationLog> queryLogs(
            Integer current,
            String module,
            String action,
            String targetType,
            Long targetId
    ) {
        int pageNo = current == null || current < 1 ? 1 : current;
        return query()
                .eq(StrUtil.isNotBlank(module), "module", module)
                .eq(StrUtil.isNotBlank(action), "action", action)
                .eq(StrUtil.isNotBlank(targetType), "target_type", targetType)
                .eq(targetId != null, "target_id", targetId)
                .orderByDesc("id")
                .page(new Page<>(pageNo, SystemConstants.MAX_PAGE_SIZE));
    }

    public String exportLogs(
            String module,
            String action,
            String targetType,
            Long targetId
    ) {
        List<OperationLog> logs = query()
                .eq(StrUtil.isNotBlank(module), "module", module)
                .eq(StrUtil.isNotBlank(action), "action", action)
                .eq(StrUtil.isNotBlank(targetType), "target_type", targetType)
                .eq(targetId != null, "target_id", targetId)
                .orderByDesc("id")
                .last("LIMIT " + Math.max(1, auditProperties.getExportLimit()))
                .list();

        StringBuilder builder = new StringBuilder();
        appendCsvRow(builder, "id", "actor_id", "actor_name", "actor_role", "module", "action",
                "target_type", "target_id", "target_key", "success", "error_message", "create_time");
        logs.forEach(logEntry -> appendCsvRow(builder,
                logEntry.getId(),
                logEntry.getActorId(),
                logEntry.getActorName(),
                logEntry.getActorRole(),
                logEntry.getModule(),
                logEntry.getAction(),
                logEntry.getTargetType(),
                logEntry.getTargetId(),
                logEntry.getTargetKey(),
                logEntry.getSuccess(),
                logEntry.getErrorMessage(),
                logEntry.getCreateTime()
        ));
        return builder.toString();
    }

    @Transactional
    public Long cleanupExpiredLogs() {
        LocalDateTime cutoff = retentionCutoff();
        Long count = query().lt("create_time", cutoff).count();
        if (count > 0) {
            remove(new QueryWrapper<OperationLog>().lt("create_time", cutoff));
        }
        return count;
    }

    @AgentTool(
            name = "get_audit_retention_status",
            description = "Get audit retention and cleanup status",
            requiredPermissions = {PermissionCodes.AUDIT_READ}
    )
    public Map<String, Object> retentionStatus() {
        LocalDateTime cutoff = retentionCutoff();
        Long expired = query().lt("create_time", cutoff).count();
        Long total = query().count();
        return Map.of(
                "retentionDays", auditProperties.getRetentionDays(),
                "exportLimit", auditProperties.getExportLimit(),
                "cutoff", cutoff,
                "expired", expired,
                "total", total
        );
    }

    private LocalDateTime retentionCutoff() {
        return LocalDateTime.now().minusDays(Math.max(1, auditProperties.getRetentionDays()));
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return JSONUtil.toJsonStr(value);
        } catch (Exception error) {
            return String.valueOf(value);
        }
    }

    private void appendCsvRow(StringBuilder builder, Object... values) {
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            appendCsvValue(builder, values[index]);
        }
        builder.append('\n');
    }

    private void appendCsvValue(StringBuilder builder, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value);
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            builder.append('"').append(text.replace("\"", "\"\"")).append('"');
            return;
        }
        builder.append(text);
    }
}
