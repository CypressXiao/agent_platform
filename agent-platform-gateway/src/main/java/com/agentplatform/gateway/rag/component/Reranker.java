package com.agentplatform.gateway.rag.component;

import com.agentplatform.gateway.rag.strategy.AdvancedRagStrategy.RankedResult;
import com.agentplatform.gateway.vector.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 重排序器
 * 使用 Cross-Encoder 模型对检索结果进行重排序
 * 支持本地模型或远程 API（如 Cohere Rerank、BGE-Reranker）
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class Reranker {

    @Value("${agent-platform.rag.reranker.enabled:true}")
    private boolean enabled;

    @Value("${agent-platform.rag.reranker.provider:local}")
    private String provider; // local, cohere, bge-api, enterprise

    @Value("${agent-platform.rag.reranker.api-url:}")
    private String apiUrl;

    @Value("${agent-platform.rag.reranker.api-key:}")
    private String apiKey;

    @Value("${agent-platform.rag.reranker.model:qwen3-rerank}")
    private String rerankModel;

    @Value("${agent-platform.rag.reranker.instruct:Given a web search query, retrieve relevant passages that answer the query.}")
    private String rerankInstruct;

    @Value("${agent-platform.rag.reranker.format:dashscope}")
    private String rerankFormat;  // dashscope, openai

    @Value("${agent-platform.rag.reranker.retry.max-attempts:3}")
    private int maxRetryAttempts;

    @Value("${agent-platform.rag.reranker.retry.delay-ms:500}")
    private int retryDelayMs;

    private final WebClient.Builder webClientBuilder;

    /**
     * 对检索结果进行重排序
     *
     * @param query 原始查询
     * @param results 检索结果
     * @param topK 返回前 K 个结果
     * @return 重排序后的结果
     */
    public List<RankedResult> rerank(String query, List<VectorStoreService.SearchResult> results, int topK) {
        if (!enabled || results.isEmpty()) {
            return results.stream()
                .limit(topK)
                .map(r -> new RankedResult(r, r.getScore(), null))
                .collect(Collectors.toList());
        }

        try {
            List<RankedResult> rankedResults = switch (provider.toLowerCase()) {
                case "cohere" -> rerankWithCohere(query, results);
                case "bge-api" -> rerankWithBgeApi(query, results);
                case "enterprise" -> rerankWithEnterprise(query, results);
                default -> rerankLocal(query, results);
            };

            // 按重排序分数排序并取 topK
            return rankedResults.stream()
                .sorted((a, b) -> Double.compare(b.getRerankScore(), a.getRerankScore()))
                .limit(topK)
                .collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("Rerank failed, falling back to original order: {}", e.getMessage());
            return results.stream()
                .limit(topK)
                .map(r -> new RankedResult(r, r.getScore(), null))
                .collect(Collectors.toList());
        }
    }

    /**
     * 本地简单重排序（基于关键词匹配度）
     * 生产环境建议使用 BGE-Reranker 或 Cohere
     */
    private List<RankedResult> rerankLocal(String query, List<VectorStoreService.SearchResult> results) {
        Set<String> queryTerms = tokenize(query.toLowerCase());

        return results.stream()
            .map(r -> {
                Set<String> docTerms = tokenize(r.getContent().toLowerCase());
                
                // 计算词重叠度
                long overlap = queryTerms.stream()
                    .filter(docTerms::contains)
                    .count();
                
                double termScore = queryTerms.isEmpty() ? 0 : (double) overlap / queryTerms.size();
                
                // 综合分数 = 向量相似度 * 0.6 + 词匹配度 * 0.4
                double combinedScore = r.getScore() * 0.6 + termScore * 0.4;
                
                return new RankedResult(r, r.getScore(), combinedScore);
            })
            .collect(Collectors.toList());
    }

    /**
     * 使用 Cohere Rerank API
     */
    private List<RankedResult> rerankWithCohere(String query, List<VectorStoreService.SearchResult> results) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Cohere API key not configured, falling back to local rerank");
            return rerankLocal(query, results);
        }

        WebClient client = webClientBuilder
            .baseUrl("https://api.cohere.ai")
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .build();

        List<String> documents = results.stream()
            .map(VectorStoreService.SearchResult::getContent)
            .collect(Collectors.toList());

        Map<String, Object> requestBody = Map.of(
            "query", query,
            "documents", documents,
            "model", "rerank-multilingual-v3.0",
            "top_n", results.size()
        );

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = client.post()
                .uri("/v1/rerank")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

            if (response != null && response.containsKey("results")) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rerankResults = (List<Map<String, Object>>) response.get("results");
                
                return rerankResults.stream()
                    .map(rr -> {
                        int index = ((Number) rr.get("index")).intValue();
                        double score = ((Number) rr.get("relevance_score")).doubleValue();
                        return new RankedResult(results.get(index), results.get(index).getScore(), score);
                    })
                    .collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.warn("Cohere rerank failed: {}", e.getMessage());
        }

        return rerankLocal(query, results);
    }

    /**
     * 使用企业内部 Reranker API
     * 支持多种 format：
     *   - dashscope: qwen3-vl-rerank 多模态格式
     *   - openai: OpenAI 兼容格式（qwen3-rerank 等）
     */
    private List<RankedResult> rerankWithEnterprise(String query, List<VectorStoreService.SearchResult> results) {
        if (apiUrl == null || apiUrl.isBlank()) {
            log.warn("Enterprise Reranker API URL not configured, falling back to local rerank");
            return rerankLocal(query, results);
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Enterprise Reranker API key not configured, falling back to local rerank");
            return rerankLocal(query, results);
        }

        Map<String, Object> requestBody = buildEnterpriseRerankRequest(query, results);

        // 重试机制
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxRetryAttempts; attempt++) {
            try {
                WebClient client = webClientBuilder
                    .baseUrl(apiUrl)
                    .defaultHeader("Authorization", "Bearer " + apiKey)
                    .build();

                @SuppressWarnings("unchecked")
                Map<String, Object> response = client.post()
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();

                List<RankedResult> parsed = parseEnterpriseRerankResponse(response, results);
                if (parsed != null) {
                    return parsed;
                }
            } catch (Exception e) {
                lastException = e;
                log.warn("Enterprise rerank attempt {}/{} failed: {}", attempt, maxRetryAttempts, e.getMessage());
                if (attempt < maxRetryAttempts) {
                    try {
                        Thread.sleep(retryDelayMs * attempt);  // 递增延迟
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        log.error("Enterprise rerank failed after {} attempts", maxRetryAttempts, lastException);
        return rerankLocal(query, results);
    }

    /**
     * 按 format 构建请求体
     */
    private Map<String, Object> buildEnterpriseRerankRequest(String query, List<VectorStoreService.SearchResult> results) {
        return switch (rerankFormat.toLowerCase()) {
            case "dashscope" -> {
                // qwen3-vl-rerank 多模态格式
                List<Map<String, Object>> documents = results.stream()
                    .map(r -> Map.<String, Object>of("text", r.getContent()))
                    .collect(Collectors.toList());
                yield Map.of(
                    "model", rerankModel,
                    "input", Map.of(
                        "query", Map.of("text", query),
                        "documents", documents
                    ),
                    "parameters", Map.of(
                        "return_documents", true,
                        "top_n", results.size()
                    )
                );
            }
            default -> {
                // OpenAI 兼容格式（qwen3-rerank 等）
                List<String> documents = results.stream()
                    .map(VectorStoreService.SearchResult::getContent)
                    .collect(Collectors.toList());
                yield Map.of(
                    "model", rerankModel,
                    "query", query,
                    "documents", documents,
                    "top_n", results.size()
                );
            }
        };
    }

    /**
     * 按 format 解析响应
     */
    @SuppressWarnings("unchecked")
    private List<RankedResult> parseEnterpriseRerankResponse(Map<String, Object> response, 
                                                              List<VectorStoreService.SearchResult> results) {
        if (response == null) return null;

        List<Map<String, Object>> rerankResults = switch (rerankFormat.toLowerCase()) {
            case "dashscope" -> {
                // {"output": {"results": [...]}}
                if (!response.containsKey("output")) yield null;
                Map<String, Object> output = (Map<String, Object>) response.get("output");
                yield (List<Map<String, Object>>) output.get("results");
            }
            default -> {
                // {"results": [...]}
                if (!response.containsKey("results")) yield null;
                yield (List<Map<String, Object>>) response.get("results");
            }
        };

        if (rerankResults == null) return null;

        return rerankResults.stream()
            .map(rr -> {
                int index = ((Number) rr.get("index")).intValue();
                double score = ((Number) rr.get("relevance_score")).doubleValue();
                return new RankedResult(results.get(index), results.get(index).getScore(), score);
            })
            .collect(Collectors.toList());
    }

    /**
     * 使用 BGE-Reranker API（自部署）
     */
    private List<RankedResult> rerankWithBgeApi(String query, List<VectorStoreService.SearchResult> results) {
        if (apiUrl == null || apiUrl.isBlank()) {
            log.warn("BGE Reranker API URL not configured, falling back to local rerank");
            return rerankLocal(query, results);
        }

        WebClient client = webClientBuilder.baseUrl(apiUrl).build();

        List<List<String>> pairs = results.stream()
            .map(r -> List.of(query, r.getContent()))
            .collect(Collectors.toList());

        Map<String, Object> requestBody = Map.of("pairs", pairs);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = client.post()
                .uri("/rerank")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

            if (response != null && response.containsKey("scores")) {
                @SuppressWarnings("unchecked")
                List<Number> scores = (List<Number>) response.get("scores");
                
                List<RankedResult> rankedResults = new ArrayList<>();
                for (int i = 0; i < results.size() && i < scores.size(); i++) {
                    rankedResults.add(new RankedResult(
                        results.get(i), 
                        results.get(i).getScore(), 
                        scores.get(i).doubleValue()
                    ));
                }
                return rankedResults;
            }
        } catch (Exception e) {
            log.warn("BGE Reranker API failed: {}", e.getMessage());
        }

        return rerankLocal(query, results);
    }

    /**
     * 简单分词（中英文混合）
     */
    private Set<String> tokenize(String text) {
        // 简单实现：按空格和标点分词，过滤短词
        return Arrays.stream(text.split("[\\s\\p{Punct}]+"))
            .filter(s -> s.length() >= 2)
            .collect(Collectors.toSet());
    }
}
