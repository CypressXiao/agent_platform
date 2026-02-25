package com.agentplatform.common.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class AuditQueryRequest {
    private String actorTid;
    private String toolName;
    private String traceId;
    private Instant from;
    private Instant to;
    private int page = 0;
    private int size = 20;
}
