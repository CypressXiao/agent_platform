package com.agentplatform.gateway.evaluation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * 评测 API
 */
@RestController
@RequestMapping("/api/v1/evaluation")
@RequiredArgsConstructor
@Tag(name = "Evaluation", description = "质量评测与指标查询")
public class EvaluationController {

    private final EvaluationService evaluationService;

    @GetMapping("/metrics/{tenantId}/{type}")
    @Operation(summary = "获取聚合指标", description = "获取指定租户和类型的聚合评测指标")
    public ResponseEntity<Map<String, Double>> getAggregateMetrics(
            @PathVariable String tenantId,
            @PathVariable EvaluationMetric.EvaluationType type) {
        return ResponseEntity.ok(evaluationService.getAggregateMetrics(tenantId, type));
    }

    @GetMapping("/metrics/{tenantId}/{type}/history")
    @Operation(summary = "获取历史指标", description = "获取指定时间范围内的评测指标")
    public ResponseEntity<List<EvaluationMetric>> getMetricHistory(
            @PathVariable String tenantId,
            @PathVariable EvaluationMetric.EvaluationType type,
            @RequestParam(defaultValue = "24") int hours) {
        Instant from = Instant.now().minus(hours, ChronoUnit.HOURS);
        Instant to = Instant.now();
        return ResponseEntity.ok(evaluationService.getMetrics(tenantId, type, from, to));
    }

    @PostMapping("/metrics")
    @Operation(summary = "记录评测指标", description = "手动记录评测指标")
    public ResponseEntity<Void> recordMetric(@RequestBody EvaluationMetric metric) {
        evaluationService.recordMetric(metric);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/summary/{tenantId}")
    @Operation(summary = "获取评测摘要", description = "获取租户的整体评测摘要")
    public ResponseEntity<Map<String, Object>> getSummary(@PathVariable String tenantId) {
        Map<String, Double> ragMetrics = evaluationService.getAggregateMetrics(
            tenantId, EvaluationMetric.EvaluationType.RAG);
        Map<String, Double> toolMetrics = evaluationService.getAggregateMetrics(
            tenantId, EvaluationMetric.EvaluationType.TOOL);
        Map<String, Double> llmMetrics = evaluationService.getAggregateMetrics(
            tenantId, EvaluationMetric.EvaluationType.LLM);

        return ResponseEntity.ok(Map.of(
            "tenantId", tenantId,
            "rag", ragMetrics,
            "tool", toolMetrics,
            "llm", llmMetrics,
            "timestamp", Instant.now().toString()
        ));
    }
}
