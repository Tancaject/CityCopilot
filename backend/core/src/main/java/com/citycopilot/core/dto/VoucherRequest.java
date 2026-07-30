package com.citycopilot.core.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * HTTP command forwarded by Core to the Commerce-owned voucher API.
 *
 * <p>This type deliberately has no MyBatis metadata because Core does not own
 * or persist voucher records.</p>
 */
@Data
@Accessors(chain = true)
public class VoucherRequest {
    private Long id;
    private Long shopId;
    private String title;
    private String subTitle;
    private String rules;
    private Long payValue;
    private Long actualValue;
    private Integer type;
    private Integer status;
    private Integer stock;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime beginTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;
}
