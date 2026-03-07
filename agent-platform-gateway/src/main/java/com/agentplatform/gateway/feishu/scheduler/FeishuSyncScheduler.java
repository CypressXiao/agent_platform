package com.agentplatform.gateway.feishu.scheduler;

import com.agentplatform.gateway.feishu.config.FeishuConfig;
import com.agentplatform.gateway.feishu.model.FeishuDocumentRegistry;
import com.agentplatform.gateway.feishu.model.FeishuSpaceRegistry;
import com.agentplatform.gateway.feishu.model.FeishuSyncTask;
import com.agentplatform.gateway.feishu.repository.FeishuDocumentRegistryRepository;
import com.agentplatform.gateway.feishu.repository.FeishuSpaceRegistryRepository;
import com.agentplatform.gateway.feishu.service.FeishuSyncService;
import com.agentplatform.gateway.feishu.service.SyncTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "feishu.sync", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FeishuSyncScheduler {

    private final FeishuConfig feishuConfig;
    private final FeishuSyncService feishuSyncService;
    private final SyncTaskService syncTaskService;
    private final FeishuDocumentRegistryRepository documentRegistryRepository;
    private final FeishuSpaceRegistryRepository spaceRegistryRepository;

    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final Semaphore concurrencySemaphore = new Semaphore(5);

    @Scheduled(cron = "${feishu.sync.cron-expression:0 0 2 * * ?}")
    public void scheduledDocumentSync() {
        if (!feishuConfig.getSync().isEnabled()) {
            log.debug("Feishu sync is disabled, skipping scheduled sync");
            return;
        }

        log.info("Starting scheduled document sync");
        try {
            scanAndCreateTasks();
        } catch (Exception e) {
            log.error("Error during scheduled document sync", e);
        }
    }

    @Scheduled(fixedDelayString = "${feishu.sync.task-poll-interval-ms:30000}")
    public void processTaskQueue() {
        if (!feishuConfig.getSync().isEnabled()) {
            return;
        }

        int batchSize = feishuConfig.getSync().getBatchSize();
        List<FeishuSyncTask> tasks = syncTaskService.getExecutableTasks(batchSize);

        if (tasks.isEmpty()) {
            return;
        }

        log.debug("Processing {} tasks from queue", tasks.size());

        for (FeishuSyncTask task : tasks) {
            if (syncTaskService.claimTask(task.getTaskId())) {
                executeTaskWithConcurrencyControl(task.getTaskId());
            }
        }
    }

    @Scheduled(cron = "${feishu.sync.space-scan-cron:0 0 3 * * ?}")
    public void scheduledSpaceScan() {
        if (!feishuConfig.getSync().isEnabled()) {
            return;
        }

        log.info("Starting scheduled space scan");
        try {
            scanSpaces();
        } catch (Exception e) {
            log.error("Error during scheduled space scan", e);
        }
    }

    public void scanAndCreateTasks() {
        int scanIntervalHours = feishuConfig.getSync().getScanIntervalHours();
        Instant threshold = Instant.now().minus(scanIntervalHours, ChronoUnit.HOURS);

        List<FeishuDocumentRegistry> documents = documentRegistryRepository.findDocumentsNeedingSync(threshold);
        log.info("Found {} documents needing sync", documents.size());

        int tasksCreated = 0;
        for (FeishuDocumentRegistry doc : documents) {
            try {
                if (feishuSyncService.checkDocumentNeedsSync(doc.getDocToken())) {
                    syncTaskService.createTask(doc.getDocToken(), "SCHEDULED", "system");
                    tasksCreated++;
                }
            } catch (Exception e) {
                log.warn("Error checking document {}: {}", doc.getDocToken(), e.getMessage());
            }
        }

        log.info("Created {} sync tasks from scheduled scan", tasksCreated);
    }

    public void scanSpaces() {
        Instant threshold = Instant.now().minus(
                feishuConfig.getSync().getScanIntervalHours(), ChronoUnit.HOURS);

        List<FeishuSpaceRegistry> spaces = spaceRegistryRepository.findSpacesNeedingScan(threshold);
        log.info("Found {} spaces needing scan", spaces.size());

        for (FeishuSpaceRegistry space : spaces) {
            try {
                List<FeishuDocumentRegistry> discovered;
                if ("WIKI".equals(space.getSpaceType())) {
                    discovered = feishuSyncService.discoverWikiDocuments(
                            space.getSpaceToken(),
                            space.getTenant(),
                            space.getScene(),
                            space.getDefaultProfile()
                    );
                } else {
                    discovered = feishuSyncService.discoverFolderDocuments(
                            space.getSpaceToken(),
                            space.getTenant(),
                            space.getScene(),
                            space.getDefaultProfile()
                    );
                }

                log.info("Discovered {} new documents in space {}", discovered.size(), space.getSpaceToken());

                // 为新发现的文档创建同步任务
                for (FeishuDocumentRegistry doc : discovered) {
                    syncTaskService.createTask(doc.getDocToken(), "INIT", "system");
                }

                // 更新空间扫描时间
                spaceRegistryRepository.updateLastScanAt(space.getSpaceToken(), Instant.now());

            } catch (Exception e) {
                log.error("Error scanning space {}: {}", space.getSpaceToken(), e.getMessage());
            }
        }
    }

    private void executeTaskWithConcurrencyControl(String taskId) {
        executorService.submit(() -> {
            try {
                concurrencySemaphore.acquire();
                try {
                    syncTaskService.executeTask(taskId);
                } finally {
                    concurrencySemaphore.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Task execution interrupted: {}", taskId);
            } catch (Exception e) {
                log.error("Error executing task {}: {}", taskId, e.getMessage());
            }
        });
    }

    public void triggerImmediateSync(String tenant) {
        log.info("Triggering immediate sync for tenant: {}", tenant);

        Instant threshold = Instant.now().minus(1, ChronoUnit.HOURS);
        List<FeishuDocumentRegistry> documents = documentRegistryRepository
                .findDocumentsNeedingSyncByTenant(tenant, threshold);

        int tasksCreated = 0;
        for (FeishuDocumentRegistry doc : documents) {
            syncTaskService.createTask(doc.getDocToken(), "MANUAL", "system");
            tasksCreated++;
        }

        log.info("Created {} immediate sync tasks for tenant {}", tasksCreated, tenant);
    }

    public void updateConcurrencyLimit(int newLimit) {
        int currentPermits = concurrencySemaphore.availablePermits();
        int diff = newLimit - feishuConfig.getSync().getMaxConcurrent();

        if (diff > 0) {
            concurrencySemaphore.release(diff);
        } else if (diff < 0) {
            concurrencySemaphore.acquireUninterruptibly(-diff);
        }

        log.info("Updated concurrency limit from {} to {}", currentPermits, newLimit);
    }
}
