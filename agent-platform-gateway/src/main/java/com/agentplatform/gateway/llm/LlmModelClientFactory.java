package com.agentplatform.gateway.llm;

import com.agentplatform.gateway.llm.model.LlmProvider;
import com.agentplatform.gateway.mcp.upstream.VaultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class LlmModelClientFactory {

    private final VaultService vaultService;
    private final Map<String, ChatClient> chatClientCache = new ConcurrentHashMap<>();

    public ChatClient getChatClient(LlmProvider provider, String modelName) {
        String cacheKey = provider.getProviderId() + ":" + modelName;
        return chatClientCache.computeIfAbsent(cacheKey, key -> createChatClient(provider, modelName));
    }

    private ChatClient createChatClient(LlmProvider provider, String modelName) {
        // OpenAI 兼容协议，目前的deepseek,qwen都支持
        OpenAiApi openAiApi = buildOpenAiApi(provider);
        OpenAiChatOptions options = new OpenAiChatOptions();
        options.setModel(modelName);
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();
        return ChatClient.builder(chatModel).build();

    }

    private OpenAiApi buildOpenAiApi(LlmProvider provider) {
        String apiKey = provider.getApiKeyRef();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Missing API key for provider " + provider.getProviderId());
        }
        String baseUrl = provider.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.openai.com";
        }
        return OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();
    }
}
