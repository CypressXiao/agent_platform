package com.agentplatform.gateway.vector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

/**
 * Hybrid Function Fallback Service
 * 当 Hybrid Search 失败时，回退到纯向量搜索
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HybridFunctionFallbackService {

    private final EmbeddingModel embeddingModel;
    private final GlobalFunctionConfig config;

    // 简单的回退实现，可根据需要扩展
    public boolean isAvailable() {
        return embeddingModel != null && config != null;
    }
}
