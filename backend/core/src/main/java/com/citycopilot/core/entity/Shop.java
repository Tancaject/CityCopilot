package com.citycopilot.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 对应 {@code shop} 表的本地店铺权威实体。
 *
 * <p>Core 负责店铺资料；GEO 与 Elasticsearch 都是可重建投影，通过 projectionVersion 防止旧事件覆盖新状态。</p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("shop")
public class Shop implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 商铺名称
     */
    private String name;

    /**
     * 商铺类型的id
     */
    private Long typeId;

    /** 归属商户用户主键；为空表示尚未分配负责人。 */
    private Long ownerUserId;

    /**
     * 商铺图片，多个图片以','隔开
     */
    private String images;

    /**
     * 商圈，例如陆家嘴
     */
    private String area;

    /**
     * 地址
     */
    private String address;

    /**
     * 经度
     */
    private Double x;

    /**
     * 纬度
     */
    private Double y;

    /**
     * 高德POI ID
     */
    private String poiId;

    /**
     * 行政区划编码
     */
    private String adcode;

    /**
     * 坐标来源
     */
    private String geoSource;

    /**
     * 坐标状态
     */
    private String geoStatus;

    /**
     * 坐标更新时间
     */
    private LocalDateTime geoUpdatedAt;

    /**
     * 均价，取整数
     */
    private Long avgPrice;

    /**
     * 销量
     */
    private Integer sold;

    /**
     * 评论数量
     */
    private Integer comments;

    /**
     * 评分，1~5分，乘10保存，避免小数
     */
    private Integer score;

    /**
     * 营业时间，例如 10:00-22:00
     */
    private String openHours;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 搜索和 GEO 投影使用的单调源版本；启用 Outbox 时与 ShopChanged 记录在同一事务中递增。
     */
    @TableField(
            value = "projection_version",
            insertStrategy = FieldStrategy.NEVER,
            updateStrategy = FieldStrategy.NEVER)
    private Long projectionVersion;
    /** 查询附近店铺时临时补充的距离，非数据库字段，单位米。 */
    @TableField(exist = false)
    private Double distance;
}
