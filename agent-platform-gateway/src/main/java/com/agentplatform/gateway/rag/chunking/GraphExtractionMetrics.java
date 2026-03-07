package com.agentplatform.gateway.rag.chunking;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 图关系提取指标监控
 */
@Component
@Slf4j
public class GraphExtractionMetrics {

    private final AtomicLong totalExtractions = new AtomicLong(0);
    private final AtomicLong successfulExtractions = new AtomicLong(0);
    private final AtomicLong failedExtractions = new AtomicLong(0);
    
    private final ConcurrentHashMap<GraphExtractionMode, AtomicLong> extractionsByMode = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> extractionsByCollection = new ConcurrentHashMap<>();
    
    private final AtomicLong totalEntitiesExtracted = new AtomicLong(0);
    private final AtomicLong totalRelationsExtracted = new AtomicLong(0);
    
    private final AtomicLong totalLlmCalls = new AtomicLong(0);
    private final AtomicLong totalLlmTokens = new AtomicLong(0);

    public GraphExtractionMetrics() {
        // 初始化模式计数器
        for (GraphExtractionMode mode : GraphExtractionMode.values()) {
            extractionsByMode.put(mode, new AtomicLong(0));
        }
    }

    /**
     * 记录提取开始
     */
    public void recordExtractionStart(GraphExtractionMode mode, String collection) {
        totalExtractions.incrementAndGet();
        extractionsByMode.get(mode).incrementAndGet();
        extractionsByCollection.computeIfAbsent(collection, k -> new AtomicLong(0)).incrementAndGet();
    }

    /**
     * 记录提取成功
     */
    public void recordExtractionSuccess(GraphExtractionMode mode, String collection, 
                                       int entityCount, int relationCount) {
        successfulExtractions.incrementAndGet();
        totalEntitiesExtracted.addAndGet(entityCount);
        totalRelationsExtracted.addAndGet(relationCount);
        
        log.debug("Extraction success: mode={}, collection={}, entities={}, relations={}", 
            mode, collection, entityCount, relationCount);
    }

    /**
     * 记录提取失败
     */
    public void recordExtractionFailure(GraphExtractionMode mode, String collection, String error) {
        failedExtractions.incrementAndGet();
        log.warn("Extraction failure: mode={}, collection={}, error={}", mode, collection, error);
    }

    /**
     * 记录 LLM 调用
     */
    public void recordLlmCall(String model, int tokens) {
        totalLlmCalls.incrementAndGet();
        totalLlmTokens.addAndGet(tokens);
    }

    /**
     * 获取指标摘要
     */
    public MetricsSummary getSummary() {
        long total = totalExtractions.get();
        long success = successfulExtractions.get();
        long failed = failedExtractions.get();
        
        double successRate = total > 0 ? (double) success / total : 0.0;
        
        return MetricsSummary.builder()
            .totalExtractions(total)
            .successfulExtractions(success)
            .failedExtractions(failed)
            .successRate(successRate)
            .totalEntitiesExtracted(totalEntitiesExtracted.get())
            .totalRelationsExtracted(totalRelationsExtracted.get())
            .totalLlmCalls(totalLlmCalls.get())
            .totalLlmTokens(totalLlmTokens.get())
            .extractionsByMode(getModeStats())
            .extractionsByCollection(getCollectionStats())
            .build();
    }

    private Map<String, Long> getModeStats() {
        return extractionsByMode.entrySet().stream()
            .collect(Collectors.toMap(
                entry -> entry.getKey().name(),
                entry -> entry.getValue().get()
            ));
    }

    private Map<String, Long> getCollectionStats() {
        return extractionsByCollection.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> entry.getValue().get()
            ));
    }

    /**
     * 重置指标
     */
    public void reset() {
        totalExtractions.set(0);
        successfulExtractions.set(0);
        failedExtractions.set(0);
        totalEntitiesExtracted.set(0);
        totalRelationsExtracted.set(0);
        totalLlmCalls.set(0);
        totalLlmTokens.set(0);
        
        extractionsByMode.values().forEach(counter -> counter.set(0));
        extractionsByCollection.values().forEach(counter -> counter.set(0));
        
        log.info("Graph extraction metrics reset");
    }

    @lombok.Data
    @lombok.Builder
    public static class MetricsSummary {
        private long totalExtractions;
        private long successfulExtractions;
        private long failedExtractions;
        private double successRate;
        private long totalEntitiesExtracted;
        private long totalRelationsExtracted;
        private long totalLlmCalls;
        private long totalLlmTokens;
        private Map<String, Long> extractionsByMode;
        private Map<String, Long> extractionsByCollection;
    }
}
