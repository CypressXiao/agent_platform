package com.agentplatform.common.model;

import java.time.Instant;
import java.util.Set;

public record CallerIdentity(
    String tenantId,
    String clientId,
    Set<String> scopes,
    String tokenId,
    Instant expiresAt
) {}
