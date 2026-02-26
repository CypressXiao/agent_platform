package com.agentplatform.gateway.rag;

import com.agentplatform.common.model.CallerIdentity;

/**
 * RAG 策略接口
 * 不同的 RAG 模式实现此接口
 */
public interface RagStrategy {
    
    /**
     * 支持的 RAG 模式
     */
    RagMode supportedMode();
    
    /**
     * 执行 RAG 查询
     */
    RagResponse execute(CallerIdentity identity, RagRequest request);
}
