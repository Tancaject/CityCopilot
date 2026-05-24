package com.hmdp.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
public class AiAssistantShopDTO {
    private Long id;
    private String name;
    private String area;
    private String address;
    private Long avgPrice;
    private Integer score;
    private Integer sold;
    private Integer comments;
    private String openHours;
    private Double distance;
    private Integer voucherCount;
    private List<String> voucherHighlights;
    private String reason;
}
