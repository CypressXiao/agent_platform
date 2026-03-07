package com.agentplatform.gateway.rag.chunking;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 图关系提取管理 API
 */
@RestController
@RequestMapping("/api/v1/graph-extraction")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Graph Extraction", description = "图关系提取管理 API")
public class GraphExtractionController {

    private final GraphExtractionMetrics metrics;
    private final GraphExtractionConfig config;

    @GetMapping("/metrics")
    @Operation(summary = "获取图关系提取指标", description = "获取运行时统计指标，包括成功率、实体数量、LLM 调用等")
    public ResponseEntity<MetricsResponse> getMetrics() {
        try {
            GraphExtractionMetrics.MetricsSummary summary = metrics.getSummary();
            
            return ResponseEntity.ok(MetricsResponse.builder()
                .success(true)
                .metrics(summary)
                .build());
                
        } catch (Exception e) {
            log.error("Failed to get graph extraction metrics", e);
            return ResponseEntity.ok(MetricsResponse.builder()
                .success(false)
                .error("Failed to retrieve metrics: " + e.getMessage())
                .build());
        }
    }

    @PostMapping("/metrics/reset")
    @Operation(summary = "重置指标", description = "重置所有运行时指标计数器")
    public ResponseEntity<ResetResponse> resetMetrics() {
        try {
            metrics.reset();
            log.info("Graph extraction metrics reset successfully");
            
            return ResponseEntity.ok(ResetResponse.builder()
                .success(true)
                .message("Metrics reset successfully")
                .build());
                
        } catch (Exception e) {
            log.error("Failed to reset graph extraction metrics", e);
            return ResponseEntity.ok(ResetResponse.builder()
                .success(false)
                .error("Failed to reset metrics: " + e.getMessage())
                .build());
        }
    }

    @GetMapping("/config")
    @Operation(summary = "获取配置信息", description = "获取当前图关系提取的配置参数")
    public ResponseEntity<ConfigResponse> getConfig() {
        try {
            return ResponseEntity.ok(ConfigResponse.builder()
                .success(true)
                .asyncEnabled(config.isAsyncEnabled())
                .llmExtraction(config.getLlmExtraction())
                .llmRelation(config.getLlmRelation())
                .performance(config.getPerformance())
                .build());
                
        } catch (Exception e) {
            log.error("Failed to get graph extraction config", e);
            return ResponseEntity.ok(ConfigResponse.builder()
                .success(false)
                .error("Failed to retrieve config: " + e.getMessage())
                .build());
        }
    }

    @GetMapping("/health")
    @Operation(summary = "健康检查", description = "检查图关系提取服务的健康状态")
    public ResponseEntity<HealthResponse> healthCheck() {
        try {
            GraphExtractionMetrics.MetricsSummary summary = metrics.getSummary();
            
            // 计算成功率
            double successRate = summary.getSuccessRate();
            boolean isHealthy = successRate >= 0.8; // 成功率不低于 80%
            
            String status = isHealthy ? "healthy" : "degraded";
            String message = isHealthy ? 
                "Graph extraction service is operating normally" :
                "Graph extraction service success rate is below threshold";
            
            return ResponseEntity.ok(HealthResponse.builder()
                .status(status)
                .healthy(isHealthy)
                .successRate(successRate)
                .totalExtractions(summary.getTotalExtractions())
                .message(message)
                .build());
                
        } catch (Exception e) {
            log.error("Failed to perform health check", e);
            return ResponseEntity.ok(HealthResponse.builder()
                .status("unhealthy")
                .healthy(false)
                .message("Health check failed: " + e.getMessage())
                .build());
        }
    }

    // ========== 响应数据结构 ==========

    @lombok.Data
    @lombok.Builder
    @Schema(description = "指标响应")
    public static class MetricsResponse {
        private boolean success;
        private String error;
        private GraphExtractionMetrics.MetricsSummary metrics;
    }

    @lombok.Data
    @lombok.Builder
    @Schema(description = "重置响应")
    public static class ResetResponse {
        private boolean success;
        private String error;
        private String message;
    }

    @lombok.Data
    @lombok.Builder
    @Schema(description = "配置响应")
    public static class ConfigResponse {
        private boolean success;
        private String error;
        private boolean asyncEnabled;
        private GraphExtractionConfig.LlmExtraction llmExtraction;
        private GraphExtractionConfig.LlmRelation llmRelation;
        private GraphExtractionConfig.Performance performance;
    }

    @lombok.Data
    @lombok.Builder
    @Schema(description = "健康检查响应")
    public static class HealthResponse {
        private String status; // healthy, degraded, unhealthy
        private boolean healthy;
        private double successRate;
        private long totalExtractions;
        private String message;
    }
}
