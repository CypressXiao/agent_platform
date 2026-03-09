package com.agentplatform.gateway.llm.provider;

import com.agentplatform.gateway.llm.provider.LlmProviderHandler.TokenUsage;
import com.agentplatform.gateway.llm.model.LlmModelConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible provider handler
 */
@Component
@Slf4j
public class OpenAiHandler implements LlmProviderHandler {
    
    @Override
    public boolean supports(String providerName) {
        return "openai".equalsIgnoreCase(providerName) || 
               "azure-openai".equalsIgnoreCase(providerName) ||
               providerName.contains("openai");
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
        return "/v1/chat/completions";
    }
    
    @Override
    public String buildAuthHeader(String apiKey) {
        return "Bearer " + apiKey;
    }
    
    @Override
    public String extractContent(Map<String, Object> response) {
        try {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> message = (Map<String, Object>) choices.getFirst().get("message");
                if (message != null) {
                    return (String) message.getOrDefault("content", "");
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract content from OpenAI response: {}", e.getMessage());
        }
        return "";
    }
    
    @Override
    public TokenUsage extractTokenUsage(Map<String, Object> response) {
        try {
            Map<String, Object> usage = (Map<String, Object>) response.get("usage");
            if (usage != null) {
                int promptTokens = usage.containsKey("prompt_tokens") ? 
                    ((Number) usage.get("prompt_tokens")).intValue() : 0;
                int completionTokens = usage.containsKey("completion_tokens") ? 
                    ((Number) usage.get("completion_tokens")).intValue() : 0;
                int totalTokens = usage.containsKey("total_tokens") ? 
                    ((Number) usage.get("total_tokens")).intValue() : promptTokens + completionTokens;
                
                return new TokenUsage(promptTokens, completionTokens, totalTokens);
            }
        } catch (Exception e) {
            log.warn("Failed to extract token usage from OpenAI response: {}", e.getMessage());
        }
        return new TokenUsage(0, 0, 0);
    }
}
