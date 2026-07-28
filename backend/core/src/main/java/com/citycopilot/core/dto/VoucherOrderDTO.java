package com.citycopilot.core.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class VoucherOrderDTO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    private Long voucherId;
    private Long shopId;
    private String shopName;
    private String voucherTitle;
    private String voucherSubTitle;
    private String voucherRules;
    private Long payValue;
    private Long actualValue;
    private Integer payType;
    private Integer status;
    private String state;
    private String statusText;
    private String failureCode;
    private LocalDateTime createTime;
    private LocalDateTime payTime;
    private LocalDateTime useTime;
    private LocalDateTime refundTime;
    private LocalDateTime updateTime;
}
