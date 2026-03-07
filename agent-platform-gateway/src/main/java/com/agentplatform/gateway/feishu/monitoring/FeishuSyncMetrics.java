package com.agentplatform.gateway.feishu.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class FeishuSyncMetrics {

    private final MeterRegistry meterRegistry;

    @Autowired
    public FeishuSyncMetrics(@Autowired(required = false) MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    private void incrementCounter(String name, String... tags) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder(name)
                .tags(tags)
                .register(meterRegistry)
                .increment();
    }

    public void markTaskCreated(String triggerType) {
        incrementCounter("feishu_sync_tasks_created", "trigger_type", triggerType);
    }

    public void markTaskCompleted(String triggerType, long durationMs) {
        incrementCounter("feishu_sync_tasks_completed", "trigger_type", triggerType);
        recordTaskDuration(triggerType, durationMs, true);
    }

    public void markTaskSkipped(String triggerType) {
        incrementCounter("feishu_sync_tasks_skipped", "trigger_type", triggerType);
    }

    public void markTaskFailed(String triggerType) {
        incrementCounter("feishu_sync_tasks_failed", "trigger_type", triggerType);
    }

    public void markVectorDeletion(int count) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("feishu_sync_vectors_deleted")
                .register(meterRegistry)
                .increment(count);
    }

    public void markVectorStoreMissing() {
        incrementCounter("feishu_sync_vectorstore_missing");
    }

    private void recordTaskDuration(String triggerType, long durationMs, boolean success) {
        if (meterRegistry == null || durationMs <= 0) {
            return;
        }
        Timer.builder("feishu_sync_task_duration")
                .tags("trigger_type", triggerType, "status", success ? "success" : "failed")
                .register(meterRegistry)
                .record(Duration.ofMillis(durationMs));
    }
}
