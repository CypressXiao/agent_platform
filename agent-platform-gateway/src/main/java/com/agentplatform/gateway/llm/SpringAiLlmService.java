package com.agentplatform.gateway.llm;

import com.agentplatform.gateway.llm.model.LlmProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 🎯 基于 Spring AI 的 LLM 服务
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SpringAiLlmService {

    private final LlmModelClientFactory clientFactory;

    @Value("${agent-platform.llm-router.timeout:30s}")
    private String defaultTimeout;

    public Map<String, Object> callChat(LlmProvider provider, String modelName,
                                        List<Map<String, Object>> messages,
                                        Double temperature, Integer maxTokens) {
        try {
            // 根据 provider/model 创建 ChatClient
            ChatClient chatClient = clientFactory.getChatClient(provider, modelName);

            // 构建消息
            StringBuilder promptBuilder = new StringBuilder();
            for (Map<String, Object> message : messages) {
                String role = (String) message.getOrDefault("role", "user");
                String content = (String) message.getOrDefault("content", "");
                promptBuilder.append(role).append(": ").append(content).append("\n");
            }

            // 调用 Spring AI
            ChatClient.CallResponseSpec response = chatClient.prompt(promptBuilder.toString()).call();

            // 转换为统一格式
            Map<String, Object> result = new HashMap<>();
            result.put("content", response.content());

            // 使用 Token 估算（Spring AI 1.0.0-M6 的 metadata API 不稳定）
            result.put("usage", estimateUsage(promptBuilder.toString(), response.content()));
            
            return result;
        } catch (Exception e) {
            log.error("Failed to call chat with Spring AI", e);
            throw new RuntimeException("Chat call failed", e);
        }
    }

    public Map<String, Object> callEmbedding(String providerId, String modelName, List<String> texts) {
        // RAG embedding is handled by separate implementation, this method can be removed or delegated
        throw new UnsupportedOperationException("Embedding is handled by separate RAG implementation");
    }
    
    /**
     * 简单的 Token 估算方法（兜底使用）
     */
    private Map<String, Object> estimateUsage(String inputText, String outputText) {
        int promptTokens = estimateTokens(inputText);
        int completionTokens = estimateTokens(outputText);
        
        return Map.of(
            "prompt_tokens", promptTokens,
            "completion_tokens", completionTokens,
            "total_tokens", promptTokens + completionTokens
        );
    }
    
    /**
     * 简单的 Token 估算方法
     * 基于字符数和大致的 Token 比例
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        // 简单估算：英文约 4 字符 = 1 token，中文约 1 字符 = 1 token
        int chineseChars = text.replaceAll("[^\\u4e00-\\u9fff]", "").length();
        int otherChars = text.length() - chineseChars;
        return chineseChars + (otherChars / 4);
    }
}
