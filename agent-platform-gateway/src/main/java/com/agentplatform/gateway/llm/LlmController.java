package com.agentplatform.gateway.llm;

import com.agentplatform.common.model.CallerIdentity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/llm")
@ConditionalOnProperty(name = "agent-platform.llm-router.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class LlmController {

    private final LlmRouterService llmRouterService;

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(
            @RequestAttribute CallerIdentity identity,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) Double temperature,
            @RequestParam(required = false) Integer maxTokens,
            @RequestBody List<Map<String, Object>> messages) {
        
        Map<String, Object> response = llmRouterService.chat(identity, model, messages, temperature, maxTokens);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/models")
    public ResponseEntity<List<String>> listAvailableModels(@RequestAttribute CallerIdentity identity) {
        // TODO: Implement model listing based on tenant's available models
        return ResponseEntity.ok(List.of("default"));
    }
}
