package com.agentplatform.gateway.vector;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.mcp.registry.BuiltinToolHandler;
import com.agentplatform.gateway.rag.RagMode;
import com.agentplatform.gateway.rag.RagRequest;
import com.agentplatform.gateway.rag.RagResponse;
import com.agentplatform.gateway.rag.RagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 查询工具
 * 封装 向量检索 + Prompt 构建 + LLM 调用 的完整流程
 * 支持 NAIVE、ADVANCED、GRAPH 三种模式
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent-platform.vector.enabled", havingValue = "true")
public class RagQueryTool implements BuiltinToolHandler {

    private final RagService ragService;

    @Override
    public String toolName() {
        return "rag_query";
    }

    @Override
    public String description() {
        return "Retrieval-Augmented Generation query. Searches vector database for relevant context, " +
               "then uses LLM to generate an answer based on the retrieved documents.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "collection", Map.of(
                    "type", "string",
                    "description", "Collection name to search in"
                ),
                "query", Map.of(
                    "type", "string",
                    "description", "User's question"
                ),
                "top_k", Map.of(
                    "type", "integer",
                    "description", "Number of documents to retrieve (default: 5)"
                ),
                "model", Map.of(
                    "type", "string",
                    "description", "LLM model to use (default: 'default')"
                ),
                "system_prompt", Map.of(
                    "type", "string",
                    "description", "Custom system prompt (optional)"
                ),
                "mode", Map.of(
                    "type", "string",
                    "description", "RAG mode: NAIVE, ADVANCED (default), or GRAPH"
                ),
                "enable_query_rewrite", Map.of(
                    "type", "boolean",
                    "description", "Enable query rewriting (default: true)"
                ),
                "enable_rerank", Map.of(
                    "type", "boolean",
                    "description", "Enable reranking (default: true)"
                ),
                "enable_context_completion", Map.of(
                    "type", "boolean",
                    "description", "Enable context completion for SOP (default: true)"
                )
            ),
            "required", List.of("collection", "query")
        );
    }

    @Override
    public Object execute(CallerIdentity identity, Map<String, Object> arguments) {
        String collection = (String) arguments.get("collection");
        String query = (String) arguments.get("query");
        int topK = arguments.containsKey("top_k") ? ((Number) arguments.get("top_k")).intValue() : 5;
        String model = arguments.containsKey("model") ? (String) arguments.get("model") : "default";
        String customSystemPrompt = (String) arguments.get("system_prompt");
        
        // 解析 RAG 模式
        RagMode mode = RagMode.ADVANCED;
        if (arguments.containsKey("mode")) {
            try {
                mode = RagMode.valueOf(((String) arguments.get("mode")).toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Invalid RAG mode: {}, using ADVANCED", arguments.get("mode"));
            }
        }

        // 构建 RAG 请求
        RagRequest request = RagRequest.builder()
            .collection(collection)
            .query(query)
            .mode(mode)
            .topK(topK)
            .model(model)
            .systemPrompt(customSystemPrompt)
            .enableQueryRewrite(getBooleanArg(arguments, "enable_query_rewrite", true))
            .enableRerank(getBooleanArg(arguments, "enable_rerank", true))
            .enableContextCompletion(getBooleanArg(arguments, "enable_context_completion", true))
            .build();

        // 执行 RAG 查询
        RagResponse response = ragService.query(identity, request);

        // 转换为 MCP Tool 响应格式
        List<Map<String, Object>> sources = response.getSources() != null 
            ? response.getSources().stream()
                .map(s -> Map.<String, Object>of(
                    "id", s.getId(),
                    "score", s.getScore(),
                    "content_preview", s.getContentPreview() != null ? s.getContentPreview() : "",
                    "metadata", s.getMetadata() != null ? s.getMetadata() : Map.of(),
                    "rerank_score", s.getRerankScore() != null ? s.getRerankScore() : 0.0
                ))
                .collect(Collectors.toList())
            : List.of();

        return Map.of(
            "success", response.isSuccess(),
            "answer", response.getAnswer() != null ? response.getAnswer() : "",
            "sources", sources,
            "model", response.getModel() != null ? response.getModel() : model,
            "mode", response.getMode() != null ? response.getMode().name() : mode.name(),
            "rewritten_query", response.getRewrittenQuery() != null ? response.getRewrittenQuery() : "",
            "usage", response.getUsage() != null ? response.getUsage() : Map.of()
        );
    }

    private boolean getBooleanArg(Map<String, Object> arguments, String key, boolean defaultValue) {
        if (!arguments.containsKey(key)) return defaultValue;
        Object value = arguments.get(key);
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) return Boolean.parseBoolean((String) value);
        return defaultValue;
    }
}
