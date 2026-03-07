package com.agentplatform.gateway.feishu.service;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.feishu.config.FeishuConfig;
import com.agentplatform.gateway.feishu.converter.FeishuDocumentConverter.DocumentMetadata;
import com.agentplatform.gateway.feishu.model.FeishuDocumentRegistry;
import com.agentplatform.gateway.feishu.model.FeishuSyncAuditLog;
import com.agentplatform.gateway.feishu.model.FeishuSyncTask;
import com.agentplatform.gateway.feishu.repository.FeishuDocumentRegistryRepository;
import com.agentplatform.gateway.feishu.repository.FeishuSyncAuditLogRepository;
import com.agentplatform.gateway.feishu.repository.FeishuSyncTaskRepository;
import com.agentplatform.gateway.feishu.service.FeishuSyncService.FetchResult;
import com.agentplatform.gateway.rag.chunking.Chunk;
import com.agentplatform.gateway.rag.chunking.CollectionNameFormatter;
import com.agentplatform.gateway.rag.chunking.ChunkingConfig;
import com.agentplatform.gateway.rag.chunking.DocumentChunker;
import com.agentplatform.gateway.rag.chunking.profile.ChunkProfile;
import com.agentplatform.gateway.rag.chunking.profile.ChunkProfileRegistry;
import com.agentplatform.gateway.vector.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyncTaskService {

    private final FeishuConfig feishuConfig;
    private final FeishuSyncService feishuSyncService;
    private final FeishuSyncTaskRepository taskRepository;
    private final FeishuDocumentRegistryRepository documentRegistryRepository;
    private final FeishuSyncAuditLogRepository auditLogRepository;
    private final DocumentChunker documentChunker;
    private final ChunkProfileRegistry profileRegistry;

    @Autowired(required = false)
    private VectorStoreService vectorStoreService;

    @Transactional
    public FeishuSyncTask createTask(String docToken, String triggerType, String triggeredBy) {
        // 检查是否已有相同文档的 PENDING 任务
        List<FeishuSyncTask> pendingTasks = taskRepository.findByDocTokenAndStatus(docToken, "PENDING");
        if (!pendingTasks.isEmpty()) {
            log.info("Task already pending for document: {}", docToken);
            return pendingTasks.get(0);
        }

        String taskId = UUID.randomUUID().toString();
        FeishuSyncTask task = FeishuSyncTask.builder()
                .taskId(taskId)
                .docToken(docToken)
                .triggerType(triggerType)
                .triggeredBy(triggeredBy)
                .status("PENDING")
                .retryCount(0)
                .maxRetries(feishuConfig.getRetry().getMaxAttempts())
                .build();

        task = taskRepository.save(task);
        log.info("Created sync task: {} for document: {}", taskId, docToken);
        return task;
    }

    @Transactional
    public List<FeishuSyncTask> createTasksForTenant(String tenant, String triggerType, String triggeredBy) {
        List<FeishuDocumentRegistry> documents = documentRegistryRepository
                .findByTenantAndStatus(tenant, "ACTIVE");

        List<FeishuSyncTask> tasks = new ArrayList<>();
        for (FeishuDocumentRegistry doc : documents) {
            if (feishuSyncService.checkDocumentNeedsSync(doc.getDocToken())) {
                tasks.add(createTask(doc.getDocToken(), triggerType, triggeredBy));
            }
        }

        log.info("Created {} sync tasks for tenant: {}", tasks.size(), tenant);
        return tasks;
    }

    public List<FeishuSyncTask> getExecutableTasks(int limit) {
        return taskRepository.findExecutableTasks(Instant.now(), PageRequest.of(0, limit));
    }

    @Transactional
    public boolean claimTask(String taskId) {
        int updated = taskRepository.claimTask(taskId, Instant.now());
        return updated > 0;
    }

    @Async
    public void executeTaskAsync(String taskId) {
        executeTask(taskId);
    }

    @Transactional
    public TaskExecutionResult executeTask(String taskId) {
        Optional<FeishuSyncTask> taskOpt = taskRepository.findByTaskId(taskId);
        if (taskOpt.isEmpty()) {
            log.warn("Task not found: {}", taskId);
            return TaskExecutionResult.error("Task not found");
        }

        FeishuSyncTask task = taskOpt.get();
        String docToken = task.getDocToken();

        // 获取文档注册信息
        Optional<FeishuDocumentRegistry> registryOpt = documentRegistryRepository.findByDocToken(docToken);
        if (registryOpt.isEmpty()) {
            failTask(taskId, "DOC_NOT_REGISTERED", "Document not found in registry");
            return TaskExecutionResult.error("Document not registered");
        }

        FeishuDocumentRegistry registry = registryOpt.get();
        String oldRevision = registry.getLastRevision();
        Instant startTime = Instant.now();

        try {
            // 1. 获取文档内容
            FetchResult fetchResult = feishuSyncService.fetchDocumentContent(docToken);
            if (!fetchResult.isSuccess()) {
                handleFetchError(task, registry, fetchResult);
                return TaskExecutionResult.error(fetchResult.getErrorMessage());
            }

            // 2. 检查是否需要同步（revision 比较）
            if (oldRevision != null && fetchResult.getRevision() != null
                    && fetchResult.getRevision().equals(oldRevision)) {
                log.info("Document {} already up to date, skipping", docToken);
                completeTask(taskId, 0, 0);
                return TaskExecutionResult.success(0);
            }

            // 3. 转换为 Markdown
            DocumentMetadata metadata = DocumentMetadata.builder()
                    .title(fetchResult.getTitle())
                    .documentId(docToken)
                    .version(fetchResult.getRevision())
                    .owner(registry.getCreatedBy())
                    .applicableScope(registry.getScene())
                    .tags(registry.getProfile())
                    .profile(registry.getProfile())
                    .tenant(registry.getTenant())
                    .scene(registry.getScene())
                    .build();

            String markdown = feishuSyncService.convertToMarkdown(docToken, fetchResult.getContent(), metadata);

            // 4. 调用分块和存储
            int chunksCreated = chunkAndStore(markdown, registry);

            // 5. 更新任务和注册表状态
            long durationMs = Instant.now().toEpochMilli() - startTime.toEpochMilli();
            completeTask(taskId, durationMs, chunksCreated);
            feishuSyncService.markDocumentSynced(docToken, fetchResult.getRevision(), Instant.now());

            // 6. 记录审计日志
            auditLogRepository.save(FeishuSyncAuditLog.builder()
                    .docToken(docToken)
                    .taskId(taskId)
                    .operation("SYNC")
                    .triggerType(task.getTriggerType())
                    .triggeredBy(task.getTriggeredBy())
                    .oldRevision(oldRevision)
                    .newRevision(fetchResult.getRevision())
                    .success(true)
                    .durationMs(durationMs)
                    .collection(registry.getCollection())
                    .chunksAffected(chunksCreated)
                    .build());

            log.info("Successfully synced document: {}, chunks: {}, duration: {}ms",
                    docToken, chunksCreated, durationMs);
            return TaskExecutionResult.success(chunksCreated);

        } catch (Exception e) {
            log.error("Failed to execute sync task: {}", taskId, e);
            failTask(taskId, "SYNC_ERROR", e.getMessage());

            auditLogRepository.save(FeishuSyncAuditLog.builder()
                    .docToken(docToken)
                    .taskId(taskId)
                    .operation("SYNC")
                    .triggerType(task.getTriggerType())
                    .triggeredBy(task.getTriggeredBy())
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build());

            return TaskExecutionResult.error(e.getMessage());
        }
    }

    private int chunkAndStore(String markdown, FeishuDocumentRegistry registry) {
        // 构建 collection 名称
        String collection = registry.getCollection();

        // 获取 profile
        String profileName = registry.getProfile();
        if (profileName == null || profileName.isBlank()) {
            throw new IllegalStateException("Missing profile on FeishuDocumentRegistry for doc " + registry.getDocToken());
        }
        
        // 检查是否为非标准文档（历史文档）
        ChunkingConfig.DocumentType documentType = ChunkingConfig.DocumentType.STANDARD;
        if ("legacy".equalsIgnoreCase(profileName) || registry.getSourceType() != null && registry.getSourceType().contains("legacy")) {
            documentType = ChunkingConfig.DocumentType.LEGACY;
        }
        
        // 如果是非标准文档，使用 legacy profile
        if (documentType == ChunkingConfig.DocumentType.LEGACY && !"legacy".equalsIgnoreCase(profileName)) {
            profileName = "legacy";
        }
        
        ChunkProfile profile = profileRegistry.get(profileName);
        ChunkingConfig originalConfig = profile.getChunkingConfig();
        
        // 创建带有 profileName 和 documentType 的配置
        ChunkingConfig config = ChunkingConfig.builder()
            .strategy(originalConfig.getStrategy())
            .chunkSize(originalConfig.getChunkSize())
            .minChunkSize(originalConfig.getMinChunkSize())
            .maxChunkSize(originalConfig.getMaxChunkSize())
            .overlap(originalConfig.getOverlap())
            .keywordStrategy(originalConfig.getKeywordStrategy())
            .maxKeywords(originalConfig.getMaxKeywords())
            .enableSparseVector(originalConfig.isEnableSparseVector())
            .sparseAnalyzer(originalConfig.getSparseAnalyzer())
            .semanticThreshold(originalConfig.getSemanticThreshold())
            .agenticModel(originalConfig.getAgenticModel())
            .embeddingModel(originalConfig.getEmbeddingModel())
            .embeddingMaxTokens(originalConfig.getEmbeddingMaxTokens())
            .charsPerToken(originalConfig.getCharsPerToken())
            .profileName(profile.getName()) // 设置 profile 名称
            .documentType(documentType) // 设置文档类型
            .build();

        if (collection == null || collection.isEmpty()) {
            String tenant = registry.getTenant();
            String scene = registry.getScene();
            String model = config.getEmbeddingModel();

            if (tenant == null || tenant.isBlank()) {
                throw new IllegalStateException("Tenant is required to build collection name for doc " + registry.getDocToken());
            }
            if (scene == null || scene.isBlank()) {
                throw new IllegalStateException("Scene is required to build collection name for doc " + registry.getDocToken());
            }
            if (model == null || model.isBlank()) {
                throw new IllegalStateException("Embedding model is required in ChunkingConfig for profile " + profileName);
            }

            collection = CollectionNameFormatter.format(tenant, scene, profileName, model);
            registry.setCollection(collection);
            documentRegistryRepository.save(registry);
        }

        // 执行分块
        List<Chunk> chunks = documentChunker.chunk(markdown, registry.getTitle(), config);

        // 使用 profile 增强 metadata
        Map<String, Object> context = new HashMap<>();
        context.put("documentName", registry.getTitle());
        context.put("docToken", registry.getDocToken());
        context.put("tenant", registry.getTenant());
        context.put("scene", registry.getScene());
        context.put("sourceType", registry.getSourceType());

        chunks = chunks.stream()
                .map(chunk -> {
                    Map<String, Object> enrichedMetadata = profile.enrichMetadata(chunk.getMetadata(), context);
                    return Chunk.builder()
                            .id(chunk.getId())
                            .content(chunk.getContent())
                            .type(chunk.getType())
                            .startOffset(chunk.getStartOffset())
                            .endOffset(chunk.getEndOffset())
                            .index(chunk.getIndex())
                            .metadata(enrichedMetadata)
                            .keywords(chunk.getKeywords())
                            .build();
                })
                .collect(Collectors.toList());

        log.info("Chunked document {} into {} chunks with profile {} to collection {}",
                registry.getDocToken(), chunks.size(), profileName, collection);

        // 存储到向量库
        if (vectorStoreService != null && !chunks.isEmpty()) {
            // 创建系统级 CallerIdentity
            CallerIdentity systemIdentity = CallerIdentity.builder()
                    .tenantId(registry.getTenant())
                    .clientId("feishu-sync-service")
                    .scopes(Set.of("vector:write"))
                    .build();

            // 转换为 VectorStoreService 的输入格式
            List<VectorStoreService.DocumentInput> documents = chunks.stream()
                    .map(chunk -> VectorStoreService.DocumentInput.builder()
                            .id(chunk.getId())
                            .content(chunk.getContent())
                            .metadata(chunk.getMetadata())
                            .build())
                    .collect(Collectors.toList());

            List<String> storedIds = vectorStoreService.store(systemIdentity, collection, documents, config.getEmbeddingModel(), config);
            log.info("Stored {} chunks to vector store for document {}", storedIds.size(), registry.getDocToken());
            return storedIds.size();
        } else {
            log.warn("VectorStoreService not available, chunks not stored for document {}", registry.getDocToken());
            return chunks.size();
        }
    }

    private void handleFetchError(FeishuSyncTask task, FeishuDocumentRegistry registry, FetchResult fetchResult) {
        if (fetchResult.isPermissionDenied()) {
            feishuSyncService.updateDocumentStatus(registry.getDocToken(), "PERMISSION_DENIED", "Fetch returned 403");
            failTask(task.getTaskId(), "PERMISSION_DENIED", "Permission denied");
        } else if (fetchResult.isNotFound()) {
            feishuSyncService.updateDocumentStatus(registry.getDocToken(), "DELETED", "Fetch returned 404");
            failTask(task.getTaskId(), "NOT_FOUND", "Document not found");
        } else {
            failTask(task.getTaskId(), "FETCH_ERROR", fetchResult.getErrorMessage());
        }
    }

    @Transactional
    public void completeTask(String taskId, long durationMs, int chunksCreated) {
        taskRepository.completeTask(taskId, Instant.now(), durationMs, chunksCreated);
    }

    @Transactional
    public void failTask(String taskId, String errorCode, String errorMessage) {
        Optional<FeishuSyncTask> taskOpt = taskRepository.findByTaskId(taskId);
        if (taskOpt.isEmpty()) {
            return;
        }

        FeishuSyncTask task = taskOpt.get();
        Instant nextRetryAt = calculateNextRetryTime(task.getRetryCount());

        taskRepository.failTask(taskId, errorCode, errorMessage, nextRetryAt);
    }

    private Instant calculateNextRetryTime(int currentRetryCount) {
        long intervalMs = (long) (feishuConfig.getRetry().getInitialIntervalMs() *
                Math.pow(feishuConfig.getRetry().getMultiplier(), currentRetryCount));
        intervalMs = Math.min(intervalMs, feishuConfig.getRetry().getMaxIntervalMs());
        return Instant.now().plusMillis(intervalMs);
    }

    @Transactional
    public void cancelTask(String taskId) {
        taskRepository.updateStatus(taskId, "CANCELLED");
    }

    public TaskStats getTaskStats() {
        long pending = taskRepository.countByStatus("PENDING");
        long running = taskRepository.countByStatus("RUNNING");
        long completed = taskRepository.countByStatus("COMPLETED");
        long failed = taskRepository.countByStatus("FAILED");
        long deadLetter = taskRepository.countDeadLetterTasks();

        return TaskStats.builder()
                .pending(pending)
                .running(running)
                .completed(completed)
                .failed(failed)
                .deadLetter(deadLetter)
                .build();
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TaskExecutionResult {
        private boolean success;
        private String errorMessage;
        private int chunksCreated;

        public static TaskExecutionResult success(int chunksCreated) {
            return TaskExecutionResult.builder()
                    .success(true)
                    .chunksCreated(chunksCreated)
                    .build();
        }

        public static TaskExecutionResult error(String message) {
            return TaskExecutionResult.builder()
                    .success(false)
                    .errorMessage(message)
                    .build();
        }
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class TaskStats {
        private long pending;
        private long running;
        private long completed;
        private long failed;
        private long deadLetter;
    }
}
