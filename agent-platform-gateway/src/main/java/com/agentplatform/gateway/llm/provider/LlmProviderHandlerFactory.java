package com.agentplatform.gateway.llm.provider;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class LlmProviderHandlerFactory {
    
    private final List<LlmProviderHandler> handlers;
    
    public LlmProviderHandler getHandler(String providerName) {
        return handlers.stream()
                .filter(handler -> handler.supports(providerName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                    "No handler found for provider: " + providerName));
    }
}
