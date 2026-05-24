package com.hmdp.dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class GeoBatchJobDTO {
    private Long id;
    private String jobId;
    private String status;
    private String city;
    private String keyword;
    private Boolean verified;
    private Boolean onlyMissing;
    private Integer limitSize;
    private Integer total;
    private Integer processed;
    private Integer success;
    private Integer failed;
    private String message;
    private Long operatorId;
    private String operatorName;
    private String startedAt;
    private String finishedAt;
}
