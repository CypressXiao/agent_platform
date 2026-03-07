package com.agentplatform.gateway.event;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 事件查询 API
 * 用于查询和回放工具调用事件
 */
@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
@Tag(name = "Events", description = "工具调用事件查询与回放")
public class EventController {

    private final EventStorageService eventStorageService;

    @GetMapping("/runs/{runId}")
    @Operation(summary = "获取指定 Run 的所有事件", description = "按 runId 查询所有工具调用事件，用于回放和分析")
    public ResponseEntity<List<ToolCallEvent>> getEventsByRunId(@PathVariable String runId) {
        List<ToolCallEvent> events = eventStorageService.getEventsByRunId(runId);
        return ResponseEntity.ok(events);
    }

    @GetMapping("/runs/{runId}/summary")
    @Operation(summary = "获取指定 Run 的摘要", description = "返回事件数量、总延迟、成功/失败统计")
    public ResponseEntity<Map<String, Object>> getRunSummary(@PathVariable String runId) {
        List<ToolCallEvent> events = eventStorageService.getEventsByRunId(runId);

        long totalLatency = events.stream().mapToLong(ToolCallEvent::getLatencyMs).sum();
        long successCount = events.stream().filter(e -> e.getStatus() == ToolCallEvent.EventStatus.SUCCESS).count();
        long errorCount = events.stream().filter(e -> e.getStatus() == ToolCallEvent.EventStatus.ERROR).count();

        return ResponseEntity.ok(Map.of(
            "runId", runId,
            "totalEvents", events.size(),
            "totalLatencyMs", totalLatency,
            "successCount", successCount,
            "errorCount", errorCount,
            "successRate", events.isEmpty() ? 0 : (double) successCount / events.size()
        ));
    }

    @DeleteMapping("/runs/{runId}")
    @Operation(summary = "删除指定 Run 的所有事件")
    public ResponseEntity<Void> deleteEventsByRunId(@PathVariable String runId) {
        eventStorageService.deleteEventsByRunId(runId);
        return ResponseEntity.noContent().build();
    }
}
