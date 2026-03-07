package com.agentplatform.gateway.job;

import com.agentplatform.common.model.Tool;
import com.agentplatform.common.model.ToolJob;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Queue 适配器
 * 将 job 写入 Redis Stream/List，由 Worker 消费
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class QueueAdapter {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final JobService jobService;

    public void enqueue(Tool tool, ToolJob job, Map<String, Object> arguments) {
        String queueTopic = tool.getQueueTopic() != null ? tool.getQueueTopic() : "tool.async.default";

        try {
            Map<String, Object> message = Map.of(
                "job_id", job.getJobId(),
                "tool_name", tool.getToolName(),
                "tool_id", tool.getToolId(),
                "source_type", tool.getSourceType(),
                "source_id", tool.getSourceId(),
                "arguments", arguments,
                "run_id", job.getRunId() != null ? job.getRunId() : "",
                "step_id", job.getStepId() != null ? job.getStepId() : "",
                "caller_tid", job.getCallerTid(),
                "timeout_ms", tool.getTimeoutMs() != null ? tool.getTimeoutMs() : 30000
            );

            String messageJson = objectMapper.writeValueAsString(message);
            
            // 写入 Redis List（简单队列）
            redisTemplate.opsForList().rightPush(queueTopic, messageJson);

            jobService.markRunning(job.getJobId());

            log.info("Job enqueued: jobId={}, topic={}", job.getJobId(), queueTopic);

        } catch (Exception e) {
            log.error("Failed to enqueue job {}: {}", job.getJobId(), e.getMessage());
            jobService.markFailed(job.getJobId(), "Failed to enqueue: " + e.getMessage());
        }
    }
}
