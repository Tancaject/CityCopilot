package com.citycopilot.agent.http;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class AgentChatRequest {
    private String message;
    private String scene;
    private String conversationId;
    private String routeName;
    private String routePath;
    private Long currentShopId;
    private Double x;
    private Double y;
}
