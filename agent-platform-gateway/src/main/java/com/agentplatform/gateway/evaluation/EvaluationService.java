package com.agentplatform.gateway.evaluation;

import com.agentplatform.gateway.event.ToolCallEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 评测服务
 * 支持 RAG/LLM/Tool 质量评测、在线 A/B 测试指标收集
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EvaluationService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String METRIC_KEY_PREFIX = "eval:metric:";
    private static final String AGGREGATE_KEY_PREFIX = "eval:agg:";
    private static final Duration METRIC_TTL = Duration.ofDays(30);

    /**
     * 记录评测指标
     */
    public void recordMetric(EvaluationMetric metric) {
        try {
            if (metric.getMetricId() == null) {
                metric.setMetricId(UUID.randomUUID().toString());
            }

            String key = METRIC_KEY_PREFIX + metric.getTenantId() + ":" + metric.getType().name().toLowerCase();
            String metricJson = objectMapper.writeValueAsString(metric);

            redisTemplate.opsForList().rightPush(key, metricJson);
            redisTemplate.expire(key, METRIC_TTL);

            // 更新聚合指标
            updateAggregateMetric(metric);

            log.debug("Recorded evaluation metric: type={}, name={}, value={}",
                metric.getType(), metric.getName(), metric.getValue());

        } catch (Exception e) {
            log.error("Failed to record evaluation metric: {}", e.getMessage());
        }
    }

    /**
     * 从 ToolCallEvent 计算并记录指标
     */
    public void evaluateToolCall(ToolCallEvent event) {
        // 工具调用成功率
        EvaluationMetric successMetric = EvaluationMetric.builder()
            .type(EvaluationMetric.EvaluationType.TOOL)
            .tenantId(event.getTenantId())
            .runId(event.getRunId())
            .name("tool_success")
            .value(event.getStatus() == ToolCallEvent.EventStatus.SUCCESS ? 1.0 : 0.0)
            .metadata(Map.of(
                "tool_name", event.getToolName(),
                "latency_ms", event.getLatencyMs()
            ))
            .build();
        recordMetric(successMetric);

        // 工具调用延迟（归一化到 0-1，假设 10s 为最大）
        double normalizedLatency = Math.min(event.getLatencyMs() / 10000.0, 1.0);
        EvaluationMetric latencyMetric = EvaluationMetric.builder()
            .type(EvaluationMetric.EvaluationType.TOOL)
            .tenantId(event.getTenantId())
            .runId(event.getRunId())
            .name("tool_latency_score")
            .value(1.0 - normalizedLatency) // 越快分数越高
            .metadata(Map.of(
                "tool_name", event.getToolName(),
                "latency_ms", event.getLatencyMs()
            ))
            .build();
        recordMetric(latencyMetric);
    }

    /**
     * 评测 RAG 检索质量
     * 
     * @param tenantId 租户 ID
     * @param query 查询
     * @param retrievedDocs 检索到的文档
     * @param relevantDocs 相关文档（ground truth）
     */
    public void evaluateRagRetrieval(String tenantId, String runId, String query,
                                      List<String> retrievedDocs, List<String> relevantDocs) {
        // 计算 Precision@K
        long relevantRetrieved = retrievedDocs.stream()
            .filter(relevantDocs::contains)
            .count();
        double precision = retrievedDocs.isEmpty() ? 0 : (double) relevantRetrieved / retrievedDocs.size();

        // 计算 Recall
        double recall = relevantDocs.isEmpty() ? 0 : (double) relevantRetrieved / relevantDocs.size();

        // 计算 F1
        double f1 = (precision + recall) == 0 ? 0 : 2 * precision * recall / (precision + recall);

        recordMetric(EvaluationMetric.builder()
            .type(EvaluationMetric.EvaluationType.RAG)
            .tenantId(tenantId)
            .runId(runId)
            .name("precision")
            .value(precision)
            .metadata(Map.of("query", query, "k", retrievedDocs.size()))
            .build());

        recordMetric(EvaluationMetric.builder()
            .type(EvaluationMetric.EvaluationType.RAG)
            .tenantId(tenantId)
            .runId(runId)
            .name("recall")
            .value(recall)
            .metadata(Map.of("query", query))
            .build());

        recordMetric(EvaluationMetric.builder()
            .type(EvaluationMetric.EvaluationType.RAG)
            .tenantId(tenantId)
            .runId(runId)
            .name("f1")
            .value(f1)
            .metadata(Map.of("query", query))
            .build());
    }

    /**
     * 获取聚合指标
     */
    public Map<String, Double> getAggregateMetrics(String tenantId, EvaluationMetric.EvaluationType type) {
        String keyPattern = AGGREGATE_KEY_PREFIX + tenantId + ":" + type.name().toLowerCase() + ":*";
        Set<String> keys = redisTemplate.keys(keyPattern);

        Map<String, Double> result = new HashMap<>();
        if (keys != null) {
            for (String key : keys) {
                String metricName = key.substring(key.lastIndexOf(":") + 1);
                String value = redisTemplate.opsForValue().get(key);
                if (value != null) {
                    try {
                        result.put(metricName, Double.parseDouble(value));
                    } catch (NumberFormatException e) {
                        log.warn("Invalid metric value for key {}: {}", key, value);
                    }
                }
            }
        }
        return result;
    }

    /**
     * 获取指定时间范围内的指标
     */
    public List<EvaluationMetric> getMetrics(String tenantId, EvaluationMetric.EvaluationType type,
                                              Instant from, Instant to) {
        String key = METRIC_KEY_PREFIX + tenantId + ":" + type.name().toLowerCase();
        List<String> metricJsonList = redisTemplate.opsForList().range(key, 0, -1);

        if (metricJsonList == null || metricJsonList.isEmpty()) {
            return List.of();
        }

        return metricJsonList.stream()
            .map(json -> {
                try {
                    return objectMapper.readValue(json, EvaluationMetric.class);
                } catch (Exception e) {
                    return null;
                }
            })
            .filter(Objects::nonNull)
            .filter(m -> m.getTimestamp().isAfter(from) && m.getTimestamp().isBefore(to))
            .collect(Collectors.toList());
    }

    /**
     * 更新聚合指标（滑动平均）
     */
    private void updateAggregateMetric(EvaluationMetric metric) {
        String key = AGGREGATE_KEY_PREFIX + metric.getTenantId() + ":" + 
                     metric.getType().name().toLowerCase() + ":" + metric.getName();

        String currentValue = redisTemplate.opsForValue().get(key);
        double newValue;

        if (currentValue == null) {
            newValue = metric.getValue();
        } else {
            // 指数移动平均
            double alpha = 0.1;
            double current = Double.parseDouble(currentValue);
            newValue = alpha * metric.getValue() + (1 - alpha) * current;
        }

        redisTemplate.opsForValue().set(key, String.valueOf(newValue));
        redisTemplate.expire(key, METRIC_TTL);
    }
}
