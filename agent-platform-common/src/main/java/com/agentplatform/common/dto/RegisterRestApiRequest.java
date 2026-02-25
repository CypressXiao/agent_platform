package com.agentplatform.common.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class RegisterRestApiRequest {
    @NotBlank
    private String serverId;
    @NotBlank
    private String baseUrl;
    private Map<String, Object> authProfile;
    private Map<String, Object> apiSpec;
    private String healthEndpoint;
    @NotBlank
    private String ownerTid;
    private List<String> tags;
    private List<RestToolDefinition> tools;

    @Data
    public static class RestToolDefinition {
        @NotBlank
        private String toolName;
        private String description;
        private Map<String, Object> inputSchema;
        private Map<String, Object> executionMapping;
        private Map<String, Object> responseMapping;
    }
}
