package com.citycopilot.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName("seckill_reservation")
public class SeckillReservation {
    @TableId(value = "order_id", type = IdType.INPUT)
    private Long orderId;
    private Long voucherId;
    private Long userId;
    private String status;
    @Version
    private Integer version;
    private String streamRecordId;
    private String failureCode;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
