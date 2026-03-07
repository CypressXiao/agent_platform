package com.agentplatform.gateway.feishu.client;

import com.agentplatform.gateway.feishu.config.FeishuConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class FeishuApiClient {

    private final FeishuConfig feishuConfig;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, TokenInfo> tokenCache = new ConcurrentHashMap<>();

    public String getTenantAccessToken() {
        String cacheKey = "tenant_access_token";
        TokenInfo cached = tokenCache.get(cacheKey);

        if (cached != null && !cached.isExpired(feishuConfig.getToken().getRefreshBeforeExpireSeconds())) {
            return cached.getToken();
        }

        synchronized (this) {
            cached = tokenCache.get(cacheKey);
            if (cached != null && !cached.isExpired(feishuConfig.getToken().getRefreshBeforeExpireSeconds())) {
                return cached.getToken();
            }

            String token = fetchTenantAccessToken();
            tokenCache.put(cacheKey, new TokenInfo(token,
                    Instant.now().plusSeconds(feishuConfig.getToken().getCacheSeconds())));
            return token;
        }
    }

    private String fetchTenantAccessToken() {
        String url = feishuConfig.getBaseUrl() + "/auth/v3/tenant_access_token/internal";

        Map<String, String> body = new HashMap<>();
        body.put("app_id", feishuConfig.getAppId());
        body.put("app_secret", feishuConfig.getAppSecret());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    JsonNode.class
            );

            JsonNode responseBody = response.getBody();
            if (responseBody != null && responseBody.has("tenant_access_token")) {
                return responseBody.get("tenant_access_token").asText();
            }

            throw new RuntimeException("Failed to get tenant_access_token: " + responseBody);
        } catch (Exception e) {
            log.error("Failed to fetch tenant access token", e);
            throw new RuntimeException("Failed to fetch tenant access token", e);
        }
    }

    public FeishuApiResponse getDocumentContent(String docToken) {
        String url = feishuConfig.getBaseUrl() + "/docx/v1/documents/" + docToken + "/raw_content";
        return executeGet(url);
    }

    public FeishuApiResponse getDocumentBlocks(String docToken) {
        String baseUrl = feishuConfig.getBaseUrl() + "/docx/v1/documents/" + docToken + "/blocks";
        ArrayNode items = objectMapper.createArrayNode();

        String pageToken = null;
        boolean hasMore;
        int page = 0;

        do {
            StringBuilder urlBuilder = new StringBuilder(baseUrl).append("?page_size=500");
            if (pageToken != null && !pageToken.isEmpty()) {
                urlBuilder.append("&page_token=").append(pageToken);
            }

            FeishuApiResponse response = executeGet(urlBuilder.toString());
            if (!response.isSuccess()) {
                return response;
            }

            JsonNode data = response.getData();
            if (data != null && data.has("items") && data.get("items").isArray()) {
                data.get("items").forEach(items::add);
            }

            hasMore = data != null && data.path("has_more").asBoolean(false);
            pageToken = (data != null && data.has("page_token")) ? data.get("page_token").asText() : null;

            if (hasMore && (pageToken == null || pageToken.isEmpty())) {
                log.warn("Blocks API returned has_more without page_token, stopping pagination. docToken={}, page={}", docToken, page);
                hasMore = false;
            }
            page++;
        } while (hasMore);

        ObjectNode mergedData = objectMapper.createObjectNode();
        mergedData.set("items", items);
        mergedData.put("has_more", false);

        return FeishuApiResponse.success(mergedData);
    }

    public FeishuApiResponse getDocumentMeta(String docToken) {
        String url = feishuConfig.getBaseUrl() + "/docx/v1/documents/" + docToken;
        return executeGet(url);
    }

    public FeishuApiResponse getWikiSpaces() {
        String url = feishuConfig.getBaseUrl() + "/wiki/v2/spaces";
        return executeGet(url);
    }

    public FeishuApiResponse getWikiNodes(String spaceId, String parentNodeToken, String pageToken) {
        StringBuilder url = new StringBuilder(feishuConfig.getBaseUrl())
                .append("/wiki/v2/spaces/").append(spaceId).append("/nodes");

        boolean hasParam = false;
        if (parentNodeToken != null && !parentNodeToken.isEmpty()) {
            url.append("?parent_node_token=").append(parentNodeToken);
            hasParam = true;
        }
        if (pageToken != null && !pageToken.isEmpty()) {
            url.append(hasParam ? "&" : "?").append("page_token=").append(pageToken);
        }

        return executeGet(url.toString());
    }

    public FeishuApiResponse getWikiNodeInfo(String nodeToken) {
        String url = feishuConfig.getBaseUrl() + "/wiki/v2/spaces/get_node?token=" + nodeToken;
        return executeGet(url);
    }

    public FeishuApiResponse getFolderChildren(String folderToken, String pageToken) {
        StringBuilder url = new StringBuilder(feishuConfig.getBaseUrl())
                .append("/drive/v1/files?folder_token=").append(folderToken);

        if (pageToken != null && !pageToken.isEmpty()) {
            url.append("&page_token=").append(pageToken);
        }

        return executeGet(url.toString());
    }

    private FeishuApiResponse executeGet(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(getTenantAccessToken());
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    JsonNode.class
            );

            JsonNode body = response.getBody();
            if (body == null) {
                return FeishuApiResponse.error(500, "Empty response body");
            }

            int code = body.has("code") ? body.get("code").asInt() : 0;
            String msg = body.has("msg") ? body.get("msg").asText() : "";
            JsonNode data = body.has("data") ? body.get("data") : null;

            if (code == 0) {
                return FeishuApiResponse.success(data);
            } else {
                return FeishuApiResponse.error(code, msg, data);
            }
        } catch (HttpClientErrorException.Forbidden e) {
            log.warn("Permission denied for URL: {}", url);
            return FeishuApiResponse.error(403, "Permission denied");
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("Resource not found for URL: {}", url);
            return FeishuApiResponse.error(404, "Resource not found");
        } catch (Exception e) {
            log.error("Failed to execute GET request: {}", url, e);
            return FeishuApiResponse.error(500, e.getMessage());
        }
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    private static class TokenInfo {
        private String token;
        private Instant expireAt;

        public boolean isExpired(int refreshBeforeSeconds) {
            return Instant.now().plusSeconds(refreshBeforeSeconds).isAfter(expireAt);
        }
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class FeishuApiResponse {
        private boolean success;
        private int code;
        private String message;
        private JsonNode data;

        public static FeishuApiResponse success(JsonNode data) {
            return FeishuApiResponse.builder()
                    .success(true)
                    .code(0)
                    .data(data)
                    .build();
        }

        public static FeishuApiResponse error(int code, String message) {
            return FeishuApiResponse.builder()
                    .success(false)
                    .code(code)
                    .message(message)
                    .build();
        }

        public static FeishuApiResponse error(int code, String message, JsonNode data) {
            return FeishuApiResponse.builder()
                    .success(false)
                    .code(code)
                    .message(message)
                    .data(data)
                    .build();
        }

        public boolean isPermissionDenied() {
            return code == 403;
        }

        public boolean isNotFound() {
            return code == 404;
        }
    }
}
