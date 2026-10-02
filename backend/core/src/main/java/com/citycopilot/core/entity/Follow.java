package com.citycopilot.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 对应 {@code user_follow} 表的用户关注关系，记录“谁关注了谁”。 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("user_follow")
public class Follow implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 发起关注的用户主键
     */
    private Long userId;

    /**
     * 被关注用户主键
     */
    private Long followUserId;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;


}
