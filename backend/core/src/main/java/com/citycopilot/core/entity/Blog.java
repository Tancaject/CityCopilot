package com.citycopilot.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 对应 {@code note} 表的探店笔记实体。
 *
 * <p>持久化字段保存作者、店铺和内容；头像、昵称及当前用户点赞状态由查询服务临时补充。</p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("note")
public class Blog implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    /**
     * 关联店铺主键
     */
    private Long shopId;
    /**
     * 用户id
     */
    private Long userId;
    /**
     * 作者头像，非数据库字段
     */
    @TableField(exist = false)
    private String icon;
    /**
     * 作者昵称，非数据库字段
     */
    @TableField(exist = false)
    private String name;
    /**
     * 当前登录用户是否点赞，非数据库字段
     */
    @TableField(exist = false)
    private Boolean isLike;

    /**
     * 标题
     */
    private String title;

    /**
     * 探店的照片，最多9张，多张以","隔开
     */
    private String images;

    /**
     * 探店的文字描述
     */
    private String content;

    /**
     * 点赞数量
     */
    private Integer liked;

    /**
     * 评论数量
     */
    private Integer comments;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;


}
