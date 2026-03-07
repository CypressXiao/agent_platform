package com.agentplatform.gateway.feishu.service;

import com.agentplatform.gateway.feishu.client.FeishuApiClient;
import com.agentplatform.gateway.feishu.client.FeishuApiClient.FeishuApiResponse;
import com.agentplatform.gateway.feishu.converter.FeishuDocumentConverter;
import com.agentplatform.gateway.feishu.converter.FeishuDocumentConverter.DocumentMetadata;
import com.agentplatform.gateway.feishu.model.FeishuDocumentRegistry;
import com.agentplatform.gateway.feishu.model.FeishuSpaceRegistry;
import com.agentplatform.gateway.feishu.model.FeishuSyncAuditLog;
import com.agentplatform.gateway.feishu.repository.FeishuDocumentRegistryRepository;
import com.agentplatform.gateway.feishu.repository.FeishuSpaceRegistryRepository;
import com.agentplatform.gateway.feishu.repository.FeishuSyncAuditLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeishuSyncService {

    private final FeishuApiClient feishuApiClient;
    private final FeishuDocumentConverter documentConverter;
    private final FeishuDocumentRegistryRepository documentRegistryRepository;
    private final FeishuSpaceRegistryRepository spaceRegistryRepository;
    private final FeishuSyncAuditLogRepository auditLogRepository;

    @Transactional
    public FeishuDocumentRegistry registerDocument(String docToken, String tenant, String scene,
                                                     String profile, String createdBy) {
        Optional<FeishuDocumentRegistry> existing = documentRegistryRepository.findByDocToken(docToken);
        if (existing.isPresent()) {
            log.info("Document already registered: {}", docToken);
            return existing.get();
        }

        if (profile == null || profile.isBlank()) {
            throw new IllegalArgumentException("Profile is required when registering document " + docToken);
        }

        // 获取文档元信息
        FeishuApiResponse metaResponse = feishuApiClient.getDocumentMeta(docToken);
        if (!metaResponse.isSuccess()) {
            handleApiError(docToken, metaResponse);
            throw new RuntimeException("Failed to get document meta: " + metaResponse.getMessage());
        }

        JsonNode data = metaResponse.getData();
        JsonNode document = data.has("document") ? data.get("document") : data;

        String title = document.has("title") ? document.get("title").asText() : "";
        FeishuDocumentRegistry registry = FeishuDocumentRegistry.builder()
                .docToken(docToken)
                .docType("docx")
                .sourceType("SINGLE")
                .title(title)
                .url("https://feishu.cn/docx/" + docToken)
                .profile(profile)
                .tenant(tenant)
                .scene(scene)
                .lastRevision(null)
                .status("ACTIVE")
                .priority(0)
                .createdBy(createdBy)
                .build();

        registry = documentRegistryRepository.save(registry);

        // 记录审计日志
        auditLogRepository.save(FeishuSyncAuditLog.builder()
                .docToken(docToken)
                .operation("REGISTER")
                .triggerType("MANUAL")
                .triggeredBy(createdBy)
                .newStatus("ACTIVE")
                .success(true)
                .build());

        log.info("Registered document: {} with title: {}", docToken, title);
        return registry;
    }

    public FetchResult fetchDocumentContent(String docToken) {
        // 获取文档元信息
        FeishuApiResponse metaResponse = feishuApiClient.getDocumentMeta(docToken);
        if (!metaResponse.isSuccess()) {
            return FetchResult.error(metaResponse.getCode(), metaResponse.getMessage());
        }

        JsonNode metaData = metaResponse.getData();
        JsonNode document = metaData.has("document") ? metaData.get("document") : metaData;
        String title = document.has("title") ? document.get("title").asText() : "";
        String revision = document.has("revision_id") ? document.get("revision_id").asText() : "";

        // 获取文档内容块
        FeishuApiResponse blocksResponse = feishuApiClient.getDocumentBlocks(docToken);
        if (!blocksResponse.isSuccess()) {
            return FetchResult.error(blocksResponse.getCode(), blocksResponse.getMessage());
        }

        return FetchResult.success(title, revision, blocksResponse.getData());
    }

    public String convertToMarkdown(String docToken, JsonNode content, DocumentMetadata metadata) {
        return documentConverter.convertToMarkdown(content, metadata);
    }

    @Transactional
    public List<FeishuDocumentRegistry> discoverWikiDocuments(String spaceToken, String tenant,
                                                               String scene, String defaultProfile) {
        if (defaultProfile == null || defaultProfile.isBlank()) {
            throw new IllegalArgumentException("defaultProfile is required when discovering wiki documents for space " + spaceToken);
        }
        List<FeishuDocumentRegistry> discovered = new ArrayList<>();
        discoverWikiNodesRecursive(spaceToken, null, null, tenant, scene, defaultProfile, discovered);
        return discovered;
    }

    private void discoverWikiNodesRecursive(String spaceId, String parentNodeToken, String pageToken,
                                             String tenant, String scene, String defaultProfile,
                                             List<FeishuDocumentRegistry> discovered) {
        FeishuApiResponse response = feishuApiClient.getWikiNodes(spaceId, parentNodeToken, pageToken);
        if (!response.isSuccess()) {
            log.warn("Failed to get wiki nodes for space: {}, parent: {}", spaceId, parentNodeToken);
            return;
        }

        JsonNode data = response.getData();
        if (data.has("items") && data.get("items").isArray()) {
            for (JsonNode item : data.get("items")) {
                String nodeToken = item.has("node_token") ? item.get("node_token").asText() : "";
                String objToken = item.has("obj_token") ? item.get("obj_token").asText() : "";
                String objType = item.has("obj_type") ? item.get("obj_type").asText() : "";
                String title = item.has("title") ? item.get("title").asText() : "";
                boolean hasChild = item.has("has_child") && item.get("has_child").asBoolean();

                // 只处理文档类型
                if ("docx".equals(objType) || "doc".equals(objType)) {
                    if (!documentRegistryRepository.existsByDocToken(objToken)) {
                        FeishuDocumentRegistry registry = FeishuDocumentRegistry.builder()
                                .docToken(objToken)
                                .docType(objType)
                                .sourceType("WIKI")
                                .title(title)
                                .url("https://feishu.cn/wiki/" + nodeToken)
                                .profile(defaultProfile)
                                .tenant(tenant)
                                .scene(scene)
                                .spaceId(spaceId)
                                .parentToken(parentNodeToken)
                                .status("ACTIVE")
                                .build();

                        registry = documentRegistryRepository.save(registry);
                        discovered.add(registry);
                        log.info("Discovered wiki document: {} - {}", objToken, title);
                    }
                }

                // 递归处理子节点
                if (hasChild) {
                    discoverWikiNodesRecursive(spaceId, nodeToken, null, tenant, scene, defaultProfile, discovered);
                }
            }
        }

        // 处理分页
        if (data.has("has_more") && data.get("has_more").asBoolean()) {
            String nextPageToken = data.has("page_token") ? data.get("page_token").asText() : "";
            if (!nextPageToken.isEmpty()) {
                discoverWikiNodesRecursive(spaceId, parentNodeToken, nextPageToken, tenant, scene, defaultProfile, discovered);
            }
        }
    }

    @Transactional
    public List<FeishuDocumentRegistry> discoverFolderDocuments(String folderToken, String tenant,
                                                                  String scene, String defaultProfile) {
        if (defaultProfile == null || defaultProfile.isBlank()) {
            throw new IllegalArgumentException("defaultProfile is required when discovering folder documents for folder " + folderToken);
        }
        List<FeishuDocumentRegistry> discovered = new ArrayList<>();
        discoverFolderRecursive(folderToken, null, tenant, scene, defaultProfile, discovered);
        return discovered;
    }

    private void discoverFolderRecursive(String folderToken, String pageToken, String tenant,
                                          String scene, String defaultProfile,
                                          List<FeishuDocumentRegistry> discovered) {
        FeishuApiResponse response = feishuApiClient.getFolderChildren(folderToken, pageToken);
        if (!response.isSuccess()) {
            log.warn("Failed to get folder children: {}", folderToken);
            return;
        }

        JsonNode data = response.getData();
        if (data.has("files") && data.get("files").isArray()) {
            for (JsonNode file : data.get("files")) {
                String token = file.has("token") ? file.get("token").asText() : "";
                String type = file.has("type") ? file.get("type").asText() : "";
                String name = file.has("name") ? file.get("name").asText() : "";

                if ("docx".equals(type) || "doc".equals(type)) {
                    if (!documentRegistryRepository.existsByDocToken(token)) {
                        FeishuDocumentRegistry registry = FeishuDocumentRegistry.builder()
                                .docToken(token)
                                .docType(type)
                                .sourceType("FOLDER")
                                .title(name)
                                .profile(defaultProfile)
                                .tenant(tenant)
                                .scene(scene)
                                .parentToken(folderToken)
                                .status("ACTIVE")
                                .build();

                        registry = documentRegistryRepository.save(registry);
                        discovered.add(registry);
                        log.info("Discovered folder document: {} - {}", token, name);
                    }
                } else if ("folder".equals(type)) {
                    // 递归处理子文件夹
                    discoverFolderRecursive(token, null, tenant, scene, defaultProfile, discovered);
                }
            }
        }

        // 处理分页
        if (data.has("has_more") && data.get("has_more").asBoolean()) {
            String nextPageToken = data.has("page_token") ? data.get("page_token").asText() : "";
            if (!nextPageToken.isEmpty()) {
                discoverFolderRecursive(folderToken, nextPageToken, tenant, scene, defaultProfile, discovered);
            }
        }
    }

    @Transactional
    public void updateDocumentStatus(String docToken, String status, String reason) {
        Optional<FeishuDocumentRegistry> existing = documentRegistryRepository.findByDocToken(docToken);
        if (existing.isEmpty()) {
            log.warn("Document not found for status update: {}", docToken);
            return;
        }

        FeishuDocumentRegistry registry = existing.get();
        String oldStatus = registry.getStatus();
        registry.setStatus(status);
        documentRegistryRepository.save(registry);

        // 记录审计日志
        auditLogRepository.save(FeishuSyncAuditLog.builder()
                .docToken(docToken)
                .operation("PERMISSION_CHANGE")
                .oldStatus(oldStatus)
                .newStatus(status)
                .success(true)
                .metadata(reason != null ? Map.of("reason", reason) : null)
                .build());

        log.info("Updated document status: {} from {} to {}", docToken, oldStatus, status);
    }

    @Transactional
    public void markDocumentSynced(String docToken, String revision, Instant syncAt) {
        documentRegistryRepository.updateSyncInfo(docToken, revision, syncAt);
    }

    public boolean checkDocumentNeedsSync(String docToken) {
        Optional<FeishuDocumentRegistry> existing = documentRegistryRepository.findByDocToken(docToken);
        if (existing.isEmpty()) {
            return true;
        }

        FeishuDocumentRegistry registry = existing.get();
        if (!"ACTIVE".equals(registry.getStatus())) {
            return false;
        }

        // 获取最新 revision
        FeishuApiResponse metaResponse = feishuApiClient.getDocumentMeta(docToken);
        if (!metaResponse.isSuccess()) {
            return false;
        }

        JsonNode data = metaResponse.getData();
        JsonNode document = data.has("document") ? data.get("document") : data;
        String latestRevision = document.has("revision_id") ? document.get("revision_id").asText() : "";

        return !latestRevision.equals(registry.getLastRevision());
    }

    private void handleApiError(String docToken, FeishuApiResponse response) {
        if (response.isPermissionDenied()) {
            updateDocumentStatus(docToken, "PERMISSION_DENIED", "API returned 403");
        } else if (response.isNotFound()) {
            updateDocumentStatus(docToken, "DELETED", "API returned 404");
        }
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class FetchResult {
        private boolean success;
        private int errorCode;
        private String errorMessage;
        private String title;
        private String revision;
        private JsonNode content;

        public static FetchResult success(String title, String revision, JsonNode content) {
            return FetchResult.builder()
                    .success(true)
                    .title(title)
                    .revision(revision)
                    .content(content)
                    .build();
        }

        public static FetchResult error(int code, String message) {
            return FetchResult.builder()
                    .success(false)
                    .errorCode(code)
                    .errorMessage(message)
                    .build();
        }

        public boolean isPermissionDenied() {
            return errorCode == 403;
        }

        public boolean isNotFound() {
            return errorCode == 404;
        }
    }
}
