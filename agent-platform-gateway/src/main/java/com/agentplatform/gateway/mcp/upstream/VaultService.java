package com.agentplatform.gateway.mcp.upstream;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for retrieving credentials from HashiCorp Vault.
 * Falls back to application properties when Vault is not configured.
 */
@Service
@Slf4j
public class VaultService {

    private final WebClient vaultClient;
    private final boolean vaultEnabled;
    private final Map<String, String> localCredentials = new ConcurrentHashMap<>();

    public VaultService(WebClient.Builder webClientBuilder,
                        @Value("${vault.url:}") String vaultUrl,
                        @Value("${vault.token:}") String vaultToken,
                        @Value("${vault.enabled:false}") boolean vaultEnabled) {
        this.vaultEnabled = vaultEnabled;
        if (vaultEnabled && !vaultUrl.isBlank()) {
            this.vaultClient = webClientBuilder
                .baseUrl(vaultUrl)
                .defaultHeader("X-Vault-Token", vaultToken)
                .build();
        } else {
            this.vaultClient = null;
        }
    }

    /**
     * Get a credential by reference path.
     * Format: "vault://secret/data/path#key" or local property key.
     */
    @SuppressWarnings("unchecked")
    public String getCredential(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }

        // Local credential override
        if (localCredentials.containsKey(ref)) {
            return localCredentials.get(ref);
        }

        if (!vaultEnabled || vaultClient == null) {
            log.debug("Vault not enabled, returning ref as-is: {}", ref);
            return ref;
        }

        // Parse vault://secret/data/path#key
        if (ref.startsWith("vault://")) {
            String path = ref.substring("vault://".length());
            String key = null;
            int hashIdx = path.indexOf('#');
            if (hashIdx > 0) {
                key = path.substring(hashIdx + 1);
                path = path.substring(0, hashIdx);
            }

            try {
                Map<String, Object> response = vaultClient.get()
                    .uri("/v1/" + path)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(5));

                if (response != null && response.containsKey("data")) {
                    Map<String, Object> data = (Map<String, Object>) response.get("data");
                    if (data.containsKey("data")) {
                        data = (Map<String, Object>) data.get("data");
                    }
                    if (key != null) {
                        return String.valueOf(data.get(key));
                    }
                    return data.values().stream().findFirst().map(String::valueOf).orElse(null);
                }
            } catch (Exception e) {
                log.error("Failed to fetch credential from Vault: {}", e.getMessage());
            }
        }

        return ref;
    }

    /**
     * Register a local credential (for testing or non-Vault deployments).
     */
    public void putLocalCredential(String key, String value) {
        localCredentials.put(key, value);
    }
}
