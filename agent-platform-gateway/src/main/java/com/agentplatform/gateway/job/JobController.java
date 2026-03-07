package com.agentplatform.gateway.job;

import com.agentplatform.common.model.ToolJob;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
@Tag(name = "Job Management", description = "异步工具作业管理")
public class JobController {

    private final JobService jobService;

    @GetMapping("/{jobId}")
    @Operation(summary = "查询作业状态", description = "根据 jobId 查询异步工具作业的状态和结果")
    public ResponseEntity<JobResponse> getJob(@PathVariable String jobId) {
        return jobService.getJob(jobId)
            .map(job -> ResponseEntity.ok(toResponse(job)))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    @Operation(summary = "查询作业列表", description = "根据 runId 或 conversationId 查询作业列表")
    public ResponseEntity<List<JobResponse>> listJobs(
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) String conversationId) {
        
        List<ToolJob> jobs;
        if (runId != null) {
            jobs = jobService.getJobsByRunId(runId);
        } else if (conversationId != null) {
            jobs = jobService.getJobsByConversationId(conversationId);
        } else {
            return ResponseEntity.badRequest().build();
        }

        return ResponseEntity.ok(jobs.stream().map(this::toResponse).toList());
    }

    @PostMapping("/{jobId}/callback")
    @Operation(summary = "作业回调", description = "工具完成后回调平台，更新作业状态和结果")
    public ResponseEntity<Void> callback(
            @PathVariable String jobId,
            @RequestBody JobCallbackRequest request) {
        
        if ("SUCCEEDED".equals(request.status())) {
            jobService.markSucceeded(jobId, request.result());
        } else if ("FAILED".equals(request.status())) {
            jobService.markFailed(jobId, request.errorMessage());
        } else {
            return ResponseEntity.badRequest().build();
        }

        return ResponseEntity.ok().build();
    }

    private JobResponse toResponse(ToolJob job) {
        return new JobResponse(
            job.getJobId(),
            job.getRunId(),
            job.getStepId(),
            job.getConversationId(),
            job.getToolName(),
            job.getStatus(),
            job.getResult(),
            job.getErrorMessage(),
            job.getEstimatedWaitSeconds(),
            job.getCreatedAt(),
            job.getStartedAt(),
            job.getCompletedAt()
        );
    }

    public record JobResponse(
        String jobId,
        String runId,
        String stepId,
        String conversationId,
        String toolName,
        String status,
        Map<String, Object> result,
        String errorMessage,
        Integer estimatedWaitSeconds,
        java.time.Instant createdAt,
        java.time.Instant startedAt,
        java.time.Instant completedAt
    ) {}

    public record JobCallbackRequest(
        String status,
        Map<String, Object> result,
        String errorMessage
    ) {}
}
