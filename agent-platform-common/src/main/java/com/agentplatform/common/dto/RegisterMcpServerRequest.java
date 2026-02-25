package com.agentplatform.common.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class RegisterMcpServerRequest {
    @NotBlank
    private String serverId;
    @NotBlank
    private String baseUrl;
    private String sseEndpoint;
    private String transport = "streamable_http";
    private Map<String, Object> authProfile;
    @NotBlank
    private String ownerTid;
    private List<String> tags;
}
