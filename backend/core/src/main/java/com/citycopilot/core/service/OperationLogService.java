package com.citycopilot.core.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.citycopilot.core.config.AuditProperties;
import com.citycopilot.core.dto.UserDTO;
import com.citycopilot.core.entity.OperationLog;
import com.citycopilot.core.mapper.OperationLogMapper;
import com.citycopilot.core.utils.SystemConstants;
import com.citycopilot.core.utils.UserHolder;
import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 业务操作审计服务，保存操作者、目标对象、前后快照和执行结果。
 *
 * <p>后台审计查询、CSV 导出和保留期清理都以 {@link OperationLog} 为统一数据源。</p>
 */
@Slf4j
@Service
public class OperationLogService extends ServiceImpl<OperationLogMapper, OperationLog> {

    /** 审计日志保留天数和单次导出上限。 */
    @Resource
    private AuditProperties auditProperties;

    /**
     * 记录一次业务操作。
     *
     * <p>使用独立事务并吞掉审计写入异常，避免审计设施故障改变原业务结果。</p>
     *
     * @param module 业务模块，如 {@code shop} 或 {@code geo}
     * @param action 稳定操作名称
     * @param targetType 目标资源类型
     * @param targetId 可选目标主键
     * @param targetKey 可读或外部目标标识
     * @param beforeData 变更前快照
     * @param afterData 变更后快照或请求数据
     * @param success 业务操作是否成功
     * @param errorMessage 失败原因；成功时为空
     */
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
            // 审计失败不阻塞业务，但必须可见：记录错误并计数，便于告警。
            Metrics.counter("citycopilot.audit.write.failure", "module", String.valueOf(module)).increment();
            log.error("Operation audit write failed for {}/{}", module, action, error);
        }
    }

    /**
     * 按可选条件分页查询最新审计日志。
     *
     * @param current 从 1 开始的页码
     * @param module 可选业务模块
     * @param action 可选操作名称
     * @param targetType 可选目标资源类型
     * @param targetId 可选目标主键
     * @return 按主键倒序排列的日志页
     */
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

    /**
     * 按过滤条件导出受配置上限约束的 CSV 文本。
     *
     * @param module 可选业务模块
     * @param action 可选操作名称
     * @param targetType 可选目标资源类型
     * @param targetId 可选目标主键
     * @return 包含表头的 CSV 内容
     */
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

    /**
     * 删除早于当前保留期边界的审计日志。
     *
     * @return 清理前统计出的过期日志数量
     */
    @Transactional
    public Long cleanupExpiredLogs() {
        LocalDateTime cutoff = retentionCutoff();
        Long count = query().lt("create_time", cutoff).count();
        if (count > 0) {
            remove(new QueryWrapper<OperationLog>().lt("create_time", cutoff));
        }
        return count;
    }

    /**
     * 汇总审计保留策略与当前过期数量。
     *
     * @return 保留天数、导出上限、截止时间及数量统计
     */
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

    /** @return 按配置保留天数计算的最早保留时间 */
    private LocalDateTime retentionCutoff() {
        return LocalDateTime.now().minusDays(Math.max(1, auditProperties.getRetentionDays()));
    }

    /**
     * 尽力把任意快照转为 JSON；不可序列化时退化为字符串，避免影响审计主流程。
     *
     * @param value 待记录对象
     * @return JSON、字符串或 {@code null}
     */
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

    /**
     * 向 CSV 缓冲区追加一行并处理列分隔。
     *
     * @param builder 导出缓冲区
     * @param values 当前行各列值
     */
    private void appendCsvRow(StringBuilder builder, Object... values) {
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            appendCsvValue(builder, values[index]);
        }
        builder.append('\n');
    }

    /**
     * 按 CSV 规则转义逗号、引号和换行。
     *
     * @param builder 导出缓冲区
     * @param value 单元格值
     */
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
