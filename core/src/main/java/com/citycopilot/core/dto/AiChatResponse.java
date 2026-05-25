package com.citycopilot.core.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Accessors(chain = true)
public class AiChatResponse {
    private String conversationId;
    private String scene;
    private String intent;
    private String message;
    private Double confidence;
    private List<AiAssistantShopDTO> shops = new ArrayList<>();
    private List<AiAssistantActionDTO> actions = new ArrayList<>();
    private Map<String, Object> diagnostics = new LinkedHashMap<>();
}
