package com.agentplatform.gateway.llm.provider;

import com.agentplatform.gateway.llm.provider.LlmProviderHandler.TokenUsage;
import com.agentplatform.gateway.llm.model.LlmModelConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Claude provider handler
 */
@Component
@Slf4j
public class ClaudeHandler implements LlmProviderHandler {
    
    @Override
    public boolean supports(String providerName) {
        return "claude".equalsIgnoreCase(providerName) || 
               "anthropic".equalsIgnoreCase(providerName);
    }
    
    @Override
    public Map<String, Object> buildRequestBody(LlmModelConfig config, 
                                               List<Map<String, Object>> messages,
                                               Double temperature, 
                                               Integer maxTokens) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", config.getModelName());
        body.put("messages", messages);
        if (temperature != null) body.put("temperature", temperature);
        if (maxTokens != null) body.put("max_tokens", maxTokens);
        return body;
    }
    
    @Override
    public String getApiEndpoint() {
        return "/v1/messages";
    }
    
    @Override
    public String buildAuthHeader(String apiKey) {
        return "Bearer " + apiKey;
    }
    
    @Override
    public String extractContent(Map<String, Object> response) {
        try {
            List<Map<String, Object>> content = (List<Map<String, Object>>) response.get("content");
            if (content != null && !content.isEmpty()) {
                return (String) content.getFirst().getOrDefault("text", "");
            }
        } catch (Exception e) {
            log.warn("Failed to extract content from Claude response: {}", e.getMessage());
        }
        return "";
    }
    
    @Override
    public TokenUsage extractTokenUsage(Map<String, Object> response) {
        try {
            Map<String, Object> usage = (Map<String, Object>) response.get("usage");
            if (usage != null) {
                int promptTokens = usage.containsKey("input_tokens") ? 
                    ((Number) usage.get("input_tokens")).intValue() : 0;
                int completionTokens = usage.containsKey("output_tokens") ? 
                    ((Number) usage.get("output_tokens")).intValue() : 0;
                int totalTokens = promptTokens + completionTokens;
                
                return new TokenUsage(promptTokens, completionTokens, totalTokens);
            }
        } catch (Exception e) {
            log.warn("Failed to extract token usage from Claude response: {}", e.getMessage());
        }
        return new TokenUsage(0, 0, 0);
    }
}
