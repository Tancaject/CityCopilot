package com.hmdp.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Map;

@Data
@Accessors(chain = true)
public class AiAssistantActionDTO {
    private String label;
    private String type;
    private String routeName;
    private String routePath;
    private Map<String, Object> query;
}
