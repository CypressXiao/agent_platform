package com.agentplatform.gateway.feishu.controller;

import com.agentplatform.gateway.feishu.model.FeishuDocumentRegistry;
import com.agentplatform.gateway.feishu.model.FeishuSyncTask;
import com.agentplatform.gateway.feishu.repository.FeishuDocumentRegistryRepository;
import com.agentplatform.gateway.feishu.repository.FeishuSyncTaskRepository;
import com.agentplatform.gateway.feishu.service.FeishuSyncService;
import com.agentplatform.gateway.feishu.service.SyncTaskService;
import com.agentplatform.gateway.feishu.service.SyncTaskService.TaskStats;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/v1/feishu/sync")
@RequiredArgsConstructor
@Tag(name = "Feishu Sync", description = "飞书文档同步管理接口")
public class FeishuSyncController {

    private final FeishuSyncService feishuSyncService;
    private final SyncTaskService syncTaskService;
    private final FeishuDocumentRegistryRepository documentRegistryRepository;
    private final FeishuSyncTaskRepository taskRepository;

    @PostMapping("/documents/register")
    @Operation(summary = "注册单个文档", description = "将飞书文档注册到同步系统")
    public ResponseEntity<FeishuDocumentRegistry> registerDocument(
            @RequestBody RegisterDocumentRequest request) {

        FeishuDocumentRegistry registry = feishuSyncService.registerDocument(
                request.getDocToken(),
                request.getTenant(),
                request.getScene(),
                request.getProfile(),
                request.getCreatedBy()
        );

        return ResponseEntity.ok(registry);
    }

    @PostMapping("/documents/{docToken}/sync")
    @Operation(summary = "触发单文档同步", description = "立即触发指定文档的同步任务")
    public ResponseEntity<FeishuSyncTask> triggerSync(
            @PathVariable String docToken,
            @Parameter(description = "触发人") @RequestParam(required = false) String triggeredBy) {

        FeishuSyncTask task = syncTaskService.createTask(docToken, "MANUAL", triggeredBy);
        syncTaskService.executeTaskAsync(task.getTaskId());

        return ResponseEntity.ok(task);
    }

    @PostMapping("/documents/batch-sync")
    @Operation(summary = "批量触发同步", description = "为指定租户的所有活跃文档创建同步任务")
    public ResponseEntity<Map<String, Object>> triggerBatchSync(
            @RequestBody BatchSyncRequest request) {

        List<FeishuSyncTask> tasks = syncTaskService.createTasksForTenant(
                request.getTenant(),
                "MANUAL",
                request.getTriggeredBy()
        );

        return ResponseEntity.ok(Map.of(
                "tasksCreated", tasks.size(),
                "tenant", request.getTenant()
        ));
    }

    @PostMapping("/spaces/discover")
    @Operation(summary = "发现空间文档", description = "扫描 Wiki 空间或目录，发现并注册所有文档")
    public ResponseEntity<Map<String, Object>> discoverSpaceDocuments(
            @RequestBody DiscoverSpaceRequest request) {

        List<FeishuDocumentRegistry> discovered;
        if ("WIKI".equalsIgnoreCase(request.getSpaceType())) {
            discovered = feishuSyncService.discoverWikiDocuments(
                    request.getSpaceToken(),
                    request.getTenant(),
                    request.getScene(),
                    request.getDefaultProfile()
            );
        } else {
            discovered = feishuSyncService.discoverFolderDocuments(
                    request.getSpaceToken(),
                    request.getTenant(),
                    request.getScene(),
                    request.getDefaultProfile()
            );
        }

        return ResponseEntity.ok(Map.of(
                "documentsDiscovered", discovered.size(),
                "spaceToken", request.getSpaceToken()
        ));
    }

    @GetMapping("/documents")
    @Operation(summary = "查询注册文档列表", description = "分页查询已注册的文档")
    public ResponseEntity<Page<FeishuDocumentRegistry>> listDocuments(
            @RequestParam String tenant,
            @RequestParam(required = false) String scene,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<FeishuDocumentRegistry> documents;
        if (scene != null && !scene.isEmpty()) {
            documents = documentRegistryRepository.findByTenantAndScene(tenant, scene, pageRequest);
        } else {
            documents = documentRegistryRepository.findByTenant(tenant, pageRequest);
        }

        return ResponseEntity.ok(documents);
    }

    @GetMapping("/documents/{docToken}")
    @Operation(summary = "查询文档详情", description = "获取指定文档的注册信息")
    public ResponseEntity<FeishuDocumentRegistry> getDocument(@PathVariable String docToken) {
        Optional<FeishuDocumentRegistry> registry = documentRegistryRepository.findByDocToken(docToken);
        return registry.map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/documents/{docToken}/status")
    @Operation(summary = "更新文档状态", description = "手动更新文档状态（如禁用、恢复）")
    public ResponseEntity<Void> updateDocumentStatus(
            @PathVariable String docToken,
            @RequestParam String status,
            @RequestParam(required = false) String reason) {

        feishuSyncService.updateDocumentStatus(docToken, status, reason);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/documents/{docToken}")
    @Operation(summary = "注销文档", description = "从同步系统中移除文档")
    public ResponseEntity<Void> unregisterDocument(@PathVariable String docToken) {
        feishuSyncService.updateDocumentStatus(docToken, "DISABLED", "Manual unregister");
        return ResponseEntity.ok().build();
    }

    @GetMapping("/tasks")
    @Operation(summary = "查询同步任务列表", description = "分页查询同步任务")
    public ResponseEntity<Page<FeishuSyncTask>> listTasks(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<FeishuSyncTask> tasks;
        if (status != null && !status.isEmpty()) {
            tasks = taskRepository.findByStatus(status, pageRequest);
        } else {
            tasks = taskRepository.findAll(pageRequest);
        }

        return ResponseEntity.ok(tasks);
    }

    @GetMapping("/tasks/{taskId}")
    @Operation(summary = "查询任务详情", description = "获取指定任务的详细信息")
    public ResponseEntity<FeishuSyncTask> getTask(@PathVariable String taskId) {
        Optional<FeishuSyncTask> task = taskRepository.findByTaskId(taskId);
        return task.map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/tasks/{taskId}/retry")
    @Operation(summary = "重试任务", description = "手动重试失败的任务")
    public ResponseEntity<Void> retryTask(@PathVariable String taskId) {
        Optional<FeishuSyncTask> taskOpt = taskRepository.findByTaskId(taskId);
        if (taskOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        FeishuSyncTask task = taskOpt.get();
        if (!"FAILED".equals(task.getStatus())) {
            return ResponseEntity.badRequest().build();
        }

        // 重置状态并执行
        task.setStatus("PENDING");
        task.setRetryCount(0);
        task.setNextRetryAt(null);
        taskRepository.save(task);

        syncTaskService.executeTaskAsync(taskId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/tasks/{taskId}/cancel")
    @Operation(summary = "取消任务", description = "取消待执行的任务")
    public ResponseEntity<Void> cancelTask(@PathVariable String taskId) {
        syncTaskService.cancelTask(taskId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/stats")
    @Operation(summary = "获取同步统计", description = "获取任务队列统计信息")
    public ResponseEntity<TaskStats> getStats() {
        return ResponseEntity.ok(syncTaskService.getTaskStats());
    }

    @GetMapping("/documents/{docToken}/check")
    @Operation(summary = "检查文档是否需要同步", description = "比较本地和远程 revision")
    public ResponseEntity<Map<String, Object>> checkDocumentSync(@PathVariable String docToken) {
        boolean needsSync = feishuSyncService.checkDocumentNeedsSync(docToken);
        return ResponseEntity.ok(Map.of(
                "docToken", docToken,
                "needsSync", needsSync
        ));
    }

    @lombok.Data
    public static class RegisterDocumentRequest {
        private String docToken;
        private String tenant;
        private String scene;
        private String profile;
        private String createdBy;
    }

    @lombok.Data
    public static class BatchSyncRequest {
        private String tenant;
        private String triggeredBy;
    }

    @lombok.Data
    public static class DiscoverSpaceRequest {
        private String spaceToken;
        private String spaceType;
        private String tenant;
        private String scene;
        private String defaultProfile;
    }
}
