package com.agentplatform.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
public class CreateGrantRequest {
    @NotBlank
    private String grantorTid;
    @NotBlank
    private String granteeTid;
    @NotEmpty
    private List<String> tools;
    private List<String> scopes;
    private Map<String, Object> constraints;
    private Instant expiresAt;
}
