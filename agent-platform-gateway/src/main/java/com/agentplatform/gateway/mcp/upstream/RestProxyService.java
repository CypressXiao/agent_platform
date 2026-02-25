package com.agentplatform.gateway.mcp.upstream;

import com.agentplatform.common.exception.McpErrorCode;
import com.agentplatform.common.exception.McpException;
import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.common.model.Tool;
import com.agentplatform.common.model.UpstreamServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Proxy service for forwarding tools/call to upstream REST APIs.
 * Constructs HTTP requests based on the tool's execution mapping.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RestProxyService {

    private final WebClient.Builder webClientBuilder;
    private final TokenExchangeService tokenExchange;

    private static final Pattern PATH_PARAM_PATTERN = Pattern.compile("\\{(\\w+)}");

    @SuppressWarnings("unchecked")
    public Object forward(CallerIdentity identity, UpstreamServer server,
                          Tool tool, Map<String, Object> arguments) {
        Map<String, Object> execMapping = tool.getExecutionMapping();
        if (execMapping == null) {
            throw new McpException(McpErrorCode.INTERNAL_ERROR,
                "No execution mapping for REST tool: " + tool.getToolName());
        }

        // Parse execution mapping
        String method = ((String) execMapping.getOrDefault("method", "POST")).toUpperCase();
        String pathTemplate = (String) execMapping.getOrDefault("path", "/");
        Map<String, String> headerMapping = (Map<String, String>) execMapping.get("headers");
        Map<String, String> queryMapping = (Map<String, String>) execMapping.get("query_params");
        String bodyTemplate = (String) execMapping.get("body_mapping");

        // Resolve path parameters
        String resolvedPath = resolvePath(pathTemplate, arguments);
        String fullUrl = server.getBaseUrl() + resolvedPath;

        // Get upstream auth
        String authHeader = tokenExchange.getUpstreamAuth(identity, server);
        int timeoutMs = tool.getTimeoutMs() != null ? tool.getTimeoutMs() : 30000;

        try {
            WebClient client = webClientBuilder.build();
            HttpMethod httpMethod = HttpMethod.valueOf(method);

            WebClient.RequestBodySpec spec = client.method(httpMethod).uri(fullUrl, uriBuilder -> {
                // Add query parameters
                if (queryMapping != null) {
                    for (Map.Entry<String, String> entry : queryMapping.entrySet()) {
                        Object value = resolveValue(entry.getValue(), arguments);
                        if (value != null) {
                            uriBuilder.queryParam(entry.getKey(), value);
                        }
                    }
                }
                return uriBuilder.build();
            });

            // Add auth header
            if (authHeader != null) {
                spec.header("Authorization", authHeader);
            }

            // Add custom headers
            if (headerMapping != null) {
                for (Map.Entry<String, String> entry : headerMapping.entrySet()) {
                    Object value = resolveValue(entry.getValue(), arguments);
                    if (value != null) {
                        spec.header(entry.getKey(), String.valueOf(value));
                    }
                }
            }

            // Build request body
            Object body = buildRequestBody(bodyTemplate, arguments);

            Map<String, Object> response;
            if (body != null && needsBody(httpMethod)) {
                response = spec.bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofMillis(timeoutMs));
            } else {
                response = spec.retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofMillis(timeoutMs));
            }

            // Apply response mapping
            return applyResponseMapping(tool.getResponseMapping(), response);

        } catch (McpException e) {
            throw e;
        } catch (Exception e) {
            log.error("REST proxy error for server {}: {}", server.getServerId(), e.getMessage(), e);
            throw new McpException(McpErrorCode.UPSTREAM_UNHEALTHY,
                "Failed to call upstream REST API: " + e.getMessage(), e);
        }
    }

    private String resolvePath(String pathTemplate, Map<String, Object> arguments) {
        Matcher matcher = PATH_PARAM_PATTERN.matcher(pathTemplate);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String paramName = matcher.group(1);
            Object value = arguments.get(paramName);
            matcher.appendReplacement(result, value != null ? String.valueOf(value) : "");
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private Object resolveValue(String template, Map<String, Object> arguments) {
        if (template == null) return null;
        if (template.startsWith("$args.")) {
            String key = template.substring("$args.".length());
            return arguments.get(key);
        }
        return template;
    }

    @SuppressWarnings("unchecked")
    private Object buildRequestBody(String bodyMapping, Map<String, Object> arguments) {
        if (bodyMapping == null || bodyMapping.equals("$args")) {
            return arguments;
        }
        if (bodyMapping.equals("$args.*")) {
            return arguments;
        }
        // Simple field selection
        if (bodyMapping.startsWith("$args.")) {
            String key = bodyMapping.substring("$args.".length());
            return arguments.get(key);
        }
        return arguments;
    }

    @SuppressWarnings("unchecked")
    private Object applyResponseMapping(Map<String, Object> responseMapping, Map<String, Object> response) {
        if (responseMapping == null || response == null) {
            return response;
        }

        String resultPath = (String) responseMapping.get("result_path");
        if (resultPath != null) {
            String[] parts = resultPath.split("\\.");
            Object current = response;
            for (String part : parts) {
                if (current instanceof Map) {
                    current = ((Map<String, Object>) current).get(part);
                } else {
                    break;
                }
            }
            return current;
        }

        return response;
    }

    private boolean needsBody(HttpMethod method) {
        return method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH;
    }
}
