package com.citycopilot.core.controller;

import com.citycopilot.core.dto.AiChatRequest;
import com.citycopilot.core.dto.Result;
import com.citycopilot.core.service.AiAssistantService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai")
public class AiController {

    @Resource
    private AiAssistantService aiAssistantService;

    @PostMapping("/chat")
    public Result chat(@RequestBody AiChatRequest request) {
        return Result.ok(aiAssistantService.chat(request));
    }
}
