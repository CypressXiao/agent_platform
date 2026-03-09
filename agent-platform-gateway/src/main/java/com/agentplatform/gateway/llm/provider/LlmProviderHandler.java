package com.agentplatform.gateway.llm.provider;

import com.agentplatform.gateway.llm.model.LlmProvider;
import com.agentplatform.gateway.llm.model.LlmModelConfig;

import java.util.List;
import java.util.Map;

/**
 * LLM Provider handler interface for different provider implementations
 */
public interface LlmProviderHandler {
    
    /**
     * Check if this handler supports the given provider
     */
    boolean supports(String providerName);
    
    /**
     * Build request body for the specific provider
     */
    Map<String, Object> buildRequestBody(LlmModelConfig config, 
                                         List<Map<String, Object>> messages,
                                         Double temperature, 
                                         Integer maxTokens);
    
    /**
     * Get API endpoint path for the provider
     */
    String getApiEndpoint();
    
    /**
     * Build authorization header
     */
    String buildAuthHeader(String apiKey);
    
    /**
     * Extract content from provider response
     */
    String extractContent(Map<String, Object> response);
    
    /**
     * Extract token usage from provider response
     */
    TokenUsage extractTokenUsage(Map<String, Object> response);
    
    /**
     * Token usage information
     */
    class TokenUsage {
        private final int promptTokens;
        private final int completionTokens;
        private final int totalTokens;
        
        public TokenUsage(int promptTokens, int completionTokens, int totalTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
        }
        
        public int getPromptTokens() { return promptTokens; }
        public int getCompletionTokens() { return completionTokens; }
        public int getTotalTokens() { return totalTokens; }
    }
}
