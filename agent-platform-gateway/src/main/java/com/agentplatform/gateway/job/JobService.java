package com.agentplatform.gateway.job;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.common.model.ToolJob;
import com.agentplatform.common.repository.ToolJobRepository;
import com.agentplatform.gateway.event.EventPublisher;
import com.agentplatform.gateway.event.ToolCallEvent;
import com.agentplatform.gateway.webhook.WebhookEvent;
import com.agentplatform.gateway.webhook.WebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobService {

    private final ToolJobRepository jobRepo;
    private final EventPublisher eventPublisher;
    private final WebhookService webhookService;

    public ToolJob createJob(CallerIdentity identity, String toolName, Map<String, Object> arguments,
                             String runId, String stepId, String conversationId, Integer estimatedWaitSeconds) {
        String jobId = "job-" + UUID.randomUUID();
        
        ToolJob job = ToolJob.builder()
            .jobId(jobId)
            .runId(runId)
            .stepId(stepId)
            .conversationId(conversationId)
            .toolName(toolName)
            .callerTid(identity.getTenantId())
            .arguments(arguments)
            .status("PENDING")
            .estimatedWaitSeconds(estimatedWaitSeconds)
            .createdAt(Instant.now())
            .build();

        return jobRepo.save(job);
    }

    @Transactional
    public void markRunning(String jobId) {
        jobRepo.findById(jobId).ifPresent(job -> {
            job.setStatus("RUNNING");
            job.setStartedAt(Instant.now());
            jobRepo.save(job);
            log.info("Job {} marked as RUNNING", jobId);
        });
    }

    @Transactional
    public void markSucceeded(String jobId, Map<String, Object> result) {
        jobRepo.findById(jobId).ifPresent(job -> {
            job.setStatus("SUCCEEDED");
            job.setResult(result);
            job.setCompletedAt(Instant.now());
            jobRepo.save(job);

            publishJobEvent(job, true);
            notifyWebhook(job);
            
            log.info("Job {} succeeded", jobId);
        });
    }

    @Transactional
    public void markFailed(String jobId, String errorMessage) {
        jobRepo.findById(jobId).ifPresent(job -> {
            job.setStatus("FAILED");
            job.setErrorMessage(errorMessage);
            job.setCompletedAt(Instant.now());
            jobRepo.save(job);

            publishJobEvent(job, false);
            notifyWebhook(job);
            
            log.warn("Job {} failed: {}", jobId, errorMessage);
        });
    }

    @Transactional
    public void markTimeout(String jobId) {
        jobRepo.findById(jobId).ifPresent(job -> {
            job.setStatus("TIMEOUT");
            job.setErrorMessage("Job execution timeout");
            job.setCompletedAt(Instant.now());
            jobRepo.save(job);

            publishJobEvent(job, false);
            notifyWebhook(job);
            
            log.warn("Job {} timed out", jobId);
        });
    }

    public Optional<ToolJob> getJob(String jobId) {
        return jobRepo.findById(jobId);
    }

    public List<ToolJob> getJobsByRunId(String runId) {
        return jobRepo.findByRunId(runId);
    }

    public List<ToolJob> getJobsByConversationId(String conversationId) {
        return jobRepo.findByConversationId(conversationId);
    }

    @Scheduled(fixedRate = 60000)
    @Transactional
    public void checkTimeouts() {
        List<ToolJob> timedOutJobs = jobRepo.findTimedOutJobs(Instant.now());
        for (ToolJob job : timedOutJobs) {
            markTimeout(job.getJobId());
        }
        if (!timedOutJobs.isEmpty()) {
            log.info("Marked {} jobs as timed out", timedOutJobs.size());
        }
    }

    private void publishJobEvent(ToolJob job, boolean success) {
        ToolCallEvent.EventStatus eventStatus = success ? ToolCallEvent.EventStatus.SUCCESS : ToolCallEvent.EventStatus.ERROR;
        Long latency = calculateLatency(job);
        
        ToolCallEvent event = ToolCallEvent.builder()
            .runId(job.getRunId())
            .stepId(job.getStepId())
            .callerId(job.getCallerTid())
            .toolName(job.getToolName())
            .arguments(job.getArguments())
            .result(success ? job.getResult() : null)
            .errorCode(success ? null : "JOB_FAILED")
            .errorMessage(success ? null : job.getErrorMessage())
            .latencyMs(latency != null ? latency : 0L)
            .status(eventStatus)
            .timestamp(Instant.now())
            .build();

        eventPublisher.publish(event);
    }

    private void notifyWebhook(ToolJob job) {
        String eventType = "SUCCEEDED".equals(job.getStatus()) 
            ? "TOOL_JOB_SUCCEEDED" 
            : "TOOL_JOB_FAILED";

        WebhookEvent webhookEvent = WebhookEvent.builder()
            .eventId(UUID.randomUUID().toString())
            .eventType(eventType)
            .tenantId(job.getCallerTid())
            .timestamp(Instant.now())
            .data(Map.of(
                "job_id", job.getJobId(),
                "run_id", job.getRunId(),
                "tool_name", job.getToolName(),
                "status", job.getStatus(),
                "result", job.getResult() != null ? job.getResult() : Map.of(),
                "error", job.getErrorMessage() != null ? job.getErrorMessage() : ""
            ))
            .build();

        webhookService.sendWebhook(webhookEvent);
    }

    private Long calculateLatency(ToolJob job) {
        if (job.getStartedAt() != null && job.getCompletedAt() != null) {
            return job.getCompletedAt().toEpochMilli() - job.getStartedAt().toEpochMilli();
        }
        return null;
    }

    public long countJobsByTenant(String tenantId) {
        return jobRepo.countByCallerTid(tenantId);
    }

    public void cleanupOldJobs(String tenantId, Instant before) {
        List<ToolJob> oldJobs = jobRepo.findByCallerTidAndCreatedAtBefore(tenantId, before);
        jobRepo.deleteAll(oldJobs);
        log.info("Cleaned up {} old jobs for tenant {}", oldJobs.size(), tenantId);
    }
}
