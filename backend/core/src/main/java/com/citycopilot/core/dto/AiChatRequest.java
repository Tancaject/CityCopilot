package com.citycopilot.core.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
public class AiChatRequest {
    private String message;
    private String scene;
    private String conversationId;
    private String routeName;
    private String routePath;
    private Long currentShopId;
    private Double x;
    private Double y;
    private List<AiChatMessageDTO> history;
}
