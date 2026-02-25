package com.agentplatform.gateway.memory.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Java HTTP client for Mem0 REST API.
 * Delegates long-term memory operations (add/search/get/delete) to the Mem0 service.
 */
@Component
@ConditionalOnProperty(name = "agent-platform.memory.mem0.enabled", havingValue = "true")
@Slf4j
public class Mem0RestClient {

    private final WebClient webClient;

    public Mem0RestClient(WebClient.Builder webClientBuilder,
                          @Value("${agent-platform.memory.mem0.url:http://localhost:8000}") String mem0Url) {
        this.webClient = webClientBuilder
            .baseUrl(mem0Url)
            .build();
    }

    /**
     * Add memory to Mem0. Mem0 will automatically:
     * 1. Extract key facts via LLM (infer=true)
     * 2. Resolve conflicts with existing memories
     * 3. Vectorize and store in Milvus
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> addMemory(List<Map<String, String>> messages, String userId,
                                          String agentId, Map<String, Object> metadata) {
        Map<String, Object> body = new HashMap<>();
        body.put("messages", messages);
        body.put("user_id", userId);
        if (agentId != null) {
            body.put("agent_id", agentId);
        }
        if (metadata != null && !metadata.isEmpty()) {
            body.put("metadata", metadata);
        }

        try {
            return webClient.post()
                .uri("/memories")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
        } catch (Exception e) {
            log.error("Mem0 addMemory failed: {}", e.getMessage(), e);
            throw new RuntimeException("Mem0 service unavailable: " + e.getMessage(), e);
        }
    }

    /**
     * Search memories by semantic similarity.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> searchMemory(String query, String userId, String agentId, int limit) {
        Map<String, Object> body = new HashMap<>();
        body.put("query", query);
        body.put("user_id", userId);
        if (agentId != null) {
            body.put("agent_id", agentId);
        }
        body.put("limit", limit);

        try {
            return webClient.post()
                .uri("/memories/search")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
        } catch (Exception e) {
            log.error("Mem0 searchMemory failed: {}", e.getMessage(), e);
            throw new RuntimeException("Mem0 service unavailable: " + e.getMessage(), e);
        }
    }

    /**
     * Get all memories for a user.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getMemories(String userId, String agentId) {
        try {
            String uri = "/memories?user_id=" + userId;
            if (agentId != null) {
                uri += "&agent_id=" + agentId;
            }
            return webClient.get()
                .uri(uri)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .block();
        } catch (Exception e) {
            log.error("Mem0 getMemories failed: {}", e.getMessage(), e);
            throw new RuntimeException("Mem0 service unavailable: " + e.getMessage(), e);
        }
    }

    /**
     * Get a specific memory by ID.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getMemory(String memoryId) {
        try {
            return webClient.get()
                .uri("/memories/{id}", memoryId)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
        } catch (Exception e) {
            log.error("Mem0 getMemory failed for {}: {}", memoryId, e.getMessage(), e);
            throw new RuntimeException("Mem0 service unavailable: " + e.getMessage(), e);
        }
    }

    /**
     * Delete a specific memory by ID.
     */
    public void deleteMemory(String memoryId) {
        try {
            webClient.delete()
                .uri("/memories/{id}", memoryId)
                .retrieve()
                .toBodilessEntity()
                .block();
        } catch (Exception e) {
            log.error("Mem0 deleteMemory failed for {}: {}", memoryId, e.getMessage(), e);
            throw new RuntimeException("Mem0 service unavailable: " + e.getMessage(), e);
        }
    }

    /**
     * Delete all memories for a user (reset).
     */
    public void resetMemories(String userId) {
        try {
            webClient.delete()
                .uri("/memories?user_id={userId}", userId)
                .retrieve()
                .toBodilessEntity()
                .block();
        } catch (Exception e) {
            log.error("Mem0 resetMemories failed for user {}: {}", userId, e.getMessage(), e);
            throw new RuntimeException("Mem0 service unavailable: " + e.getMessage(), e);
        }
    }

    /**
     * Health check for Mem0 service.
     */
    public boolean isHealthy() {
        try {
            webClient.get()
                .uri("/")
                .retrieve()
                .toBodilessEntity()
                .block();
            return true;
        } catch (Exception e) {
            log.warn("Mem0 health check failed: {}", e.getMessage());
            return false;
        }
    }
}
