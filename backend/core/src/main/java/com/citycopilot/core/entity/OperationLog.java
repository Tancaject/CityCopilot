package com.citycopilot.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 对应 {@code operation_log} 表的后台操作审计实体。
 *
 * <p>它保存操作者、目标、变更前后快照及执行结果，用于审计查询而非业务状态回滚。</p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("operation_log")
public class OperationLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 审计记录主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 操作者用户主键。 */
    private Long actorId;

    /** 操作者名称快照。 */
    private String actorName;

    /** 操作者角色快照。 */
    private String actorRole;

    /** 发生操作的业务模块。 */
    private String module;

    /** 稳定操作名称。 */
    private String action;

    /** 被操作对象类型。 */
    private String targetType;

    /** 数字型目标主键。 */
    private Long targetId;

    /** 无数字主键时使用的业务目标标识。 */
    private String targetKey;

    /** 操作前状态的 JSON 快照。 */
    private String beforeData;

    /** 操作后状态的 JSON 快照。 */
    private String afterData;

    /** 操作是否成功。 */
    private Boolean success;

    /** 失败时记录的错误说明。 */
    private String errorMessage;

    /** 审计记录创建时间。 */
    private LocalDateTime createTime;
}
