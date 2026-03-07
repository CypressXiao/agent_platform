package com.agentplatform.gateway.job;

import com.agentplatform.common.model.Tool;
import com.agentplatform.common.model.ToolJob;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/**
 * Callback 适配器
 * 调用工具的 callback_url，传递 job 信息
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CallbackAdapter {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final JobService jobService;

    @Async
    public void invoke(Tool tool, ToolJob job, Map<String, Object> arguments) {
        if (tool.getCallbackUrl() == null || tool.getCallbackUrl().isEmpty()) {
            log.error("Callback URL not configured for tool: {}", tool.getToolName());
            jobService.markFailed(job.getJobId(), "Callback URL not configured");
            return;
        }

        try {
            jobService.markRunning(job.getJobId());

            Map<String, Object> payload = Map.of(
                "job_id", job.getJobId(),
                "tool_name", tool.getToolName(),
                "arguments", arguments,
                "run_id", job.getRunId() != null ? job.getRunId() : "",
                "step_id", job.getStepId() != null ? job.getStepId() : "",
                "callback_url", buildCallbackUrl(job.getJobId())
            );

            String payloadJson = objectMapper.writeValueAsString(payload);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Job-Id", job.getJobId());
            headers.set("X-Timestamp", Instant.now().toString());

            // 添加认证
            addAuthentication(headers, tool, payloadJson);

            HttpEntity<String> request = new HttpEntity<>(payloadJson, headers);
            restTemplate.postForEntity(tool.getCallbackUrl(), request, String.class);

            log.info("Callback invoked successfully: jobId={}, url={}", job.getJobId(), tool.getCallbackUrl());

        } catch (Exception e) {
            log.error("Failed to invoke callback for job {}: {}", job.getJobId(), e.getMessage());
            jobService.markFailed(job.getJobId(), "Callback invocation failed: " + e.getMessage());
        }
    }

    private void addAuthentication(HttpHeaders headers, Tool tool, String payload) {
        if (tool.getCallbackAuth() == null) {
            return;
        }

        String authType = (String) tool.getCallbackAuth().get("type");
        if (authType == null) {
            return;
        }

        switch (authType) {
            case "API_KEY" -> {
                String apiKey = (String) tool.getCallbackAuth().get("api_key");
                String headerName = (String) tool.getCallbackAuth().getOrDefault("header_name", "Authorization");
                if (apiKey != null) {
                    headers.set(headerName, "Bearer " + apiKey);
                }
            }
            case "HMAC" -> {
                String secret = (String) tool.getCallbackAuth().get("secret");
                if (secret != null) {
                    String signature = computeHmacSignature(payload, secret);
                    headers.set("X-Signature", signature);
                }
            }
            case "OAUTH2" -> {
                String token = (String) tool.getCallbackAuth().get("access_token");
                if (token != null) {
                    headers.set("Authorization", "Bearer " + token);
                }
            }
            default -> log.warn("Unknown auth type: {}", authType);
        }
    }

    private String computeHmacSignature(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.error("Failed to compute HMAC signature: {}", e.getMessage());
            return "";
        }
    }

    private String buildCallbackUrl(String jobId) {
        return "/api/v1/jobs/" + jobId + "/callback";
    }
}
