package com.agentplatform.gateway.job;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.common.model.Tool;
import com.agentplatform.common.model.ToolJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 异步工具适配器
 * 根据 result_delivery 选择 Callback 或 Queue 模式
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AsyncToolAdapter {

    private final JobService jobService;
    private final CallbackAdapter callbackAdapter;
    private final QueueAdapter queueAdapter;

    public Map<String, Object> dispatch(CallerIdentity identity, Tool tool, Map<String, Object> arguments,
                                        String runId, String stepId, String conversationId) {
        
        // 创建 Job
        Integer estimatedWait = tool.getTimeoutMs() != null ? tool.getTimeoutMs() / 1000 : 180;
        ToolJob job = jobService.createJob(identity, tool.getToolName(), arguments, 
                                           runId, stepId, conversationId, estimatedWait);

        log.info("Created async job: jobId={}, tool={}, delivery={}", 
                 job.getJobId(), tool.getToolName(), tool.getResultDelivery());

        // 根据 result_delivery 选择适配器
        if ("CALLBACK".equals(tool.getResultDelivery())) {
            callbackAdapter.invoke(tool, job, arguments);
        } else if ("QUEUE".equals(tool.getResultDelivery())) {
            queueAdapter.enqueue(tool, job, arguments);
        } else {
            log.warn("Unknown result_delivery mode: {}, defaulting to QUEUE", tool.getResultDelivery());
            queueAdapter.enqueue(tool, job, arguments);
        }

        // 返回 PENDING 响应
        return Map.of(
            "status", "PENDING",
            "job_id", job.getJobId(),
            "estimated_wait_seconds", estimatedWait,
            "message", "Job created and processing started"
        );
    }
}
