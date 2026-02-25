package com.agentplatform.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Set;

/**
 * 调用方身份信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CallerIdentity {
    
    /**
     * 租户 ID
     */
    private String tenantId;
    
    /**
     * 客户端 ID
     */
    private String clientId;
    
    /**
     * 授权范围
     */
    private Set<String> scopes;
    
    /**
     * Token ID
     */
    private String tokenId;
    
    /**
     * 过期时间
     */
    private Instant expiresAt;
}
