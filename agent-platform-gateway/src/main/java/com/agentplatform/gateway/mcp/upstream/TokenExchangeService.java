package com.agentplatform.gateway.mcp.upstream;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.common.model.UpstreamServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/**
 * Token Exchange Service — exchanges platform tokens for upstream credentials.
 * Supports multiple upstream auth strategies: oauth2, api_key, basic, client_credentials.
 * MUST NOT pass through the caller's original token to upstream.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TokenExchangeService {

    private final VaultService vault;
    private final WebClient.Builder webClientBuilder;
    private final StringRedisTemplate redisTemplate;

    private static final Duration TOKEN_CACHE_TTL = Duration.ofMinutes(50);

    /**
     * Get the appropriate authorization header value for an upstream server.
     */
    @SuppressWarnings("unchecked")
    public String getUpstreamAuth(CallerIdentity identity, UpstreamServer server) {
        Map<String, Object> authProfile = server.getAuthProfile();
        if (authProfile == null || authProfile.isEmpty()) {
            return null;
        }

        String authType = (String) authProfile.get("type");
        if (authType == null) {
            return null;
        }

        return switch (authType) {
            case "api_key" -> {
                String keyRef = (String) authProfile.get("api_key_ref");
                String apiKey = vault.getCredential(keyRef);
                String header = (String) authProfile.getOrDefault("header", "Authorization");
                String prefix = (String) authProfile.getOrDefault("prefix", "Bearer");
                yield prefix + " " + apiKey;
            }
            case "basic" -> {
                String usernameRef = (String) authProfile.get("username_ref");
                String passwordRef = (String) authProfile.get("password_ref");
                String username = vault.getCredential(usernameRef);
                String password = vault.getCredential(passwordRef);
                String encoded = java.util.Base64.getEncoder()
                    .encodeToString((username + ":" + password).getBytes());
                yield "Basic " + encoded;
            }
            case "client_credentials" -> {
                yield exchangeClientCredentials(server, authProfile);
            }
            case "oauth2_token_exchange" -> {
                yield exchangeOAuth2Token(identity, server, authProfile);
            }
            default -> {
                log.warn("Unknown auth type for server {}: {}", server.getServerId(), authType);
                yield null;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private String exchangeClientCredentials(UpstreamServer server, Map<String, Object> authProfile) {
        String cacheKey = "upstream_token:cc:" + server.getServerId();

        // Check cache
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return "Bearer " + cached;
        }

        String tokenUrl = (String) authProfile.get("token_url");
        String clientIdRef = (String) authProfile.get("client_id_ref");
        String clientSecretRef = (String) authProfile.get("client_secret_ref");
        String scope = (String) authProfile.getOrDefault("scope", "");

        String clientId = vault.getCredential(clientIdRef);
        String clientSecret = vault.getCredential(clientSecretRef);

        WebClient client = webClientBuilder.build();
        Map<String, Object> response = client.post()
            .uri(tokenUrl)
            .headers(h -> h.setBasicAuth(clientId, clientSecret))
            .bodyValue("grant_type=client_credentials&scope=" + scope)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .retrieve()
            .bodyToMono(Map.class)
            .block(Duration.ofSeconds(10));

        if (response != null && response.containsKey("access_token")) {
            String token = (String) response.get("access_token");
            Number expiresIn = (Number) response.getOrDefault("expires_in", 3600);
            Duration ttl = Duration.ofSeconds(Math.min(expiresIn.longValue() - 60, TOKEN_CACHE_TTL.getSeconds()));
            redisTemplate.opsForValue().set(cacheKey, token, ttl);
            return "Bearer " + token;
        }

        log.error("Client credentials exchange failed for server {}", server.getServerId());
        return null;
    }

    @SuppressWarnings("unchecked")
    private String exchangeOAuth2Token(CallerIdentity identity, UpstreamServer server,
                                        Map<String, Object> authProfile) {
        String cacheKey = "upstream_token:te:" + server.getServerId() + ":" + identity.tenantId();

        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return "Bearer " + cached;
        }

        String tokenUrl = (String) authProfile.get("token_url");
        String clientIdRef = (String) authProfile.get("client_id_ref");
        String clientSecretRef = (String) authProfile.get("client_secret_ref");
        String audience = (String) authProfile.getOrDefault("audience", server.getBaseUrl());

        String clientId = vault.getCredential(clientIdRef);
        String clientSecret = vault.getCredential(clientSecretRef);

        String body = "grant_type=urn:ietf:params:oauth:grant-type:token-exchange" +
            "&subject_token_type=urn:ietf:params:oauth:token-type:jwt" +
            "&subject_token=" + identity.tokenId() +
            "&audience=" + audience;

        WebClient client = webClientBuilder.build();
        Map<String, Object> response = client.post()
            .uri(tokenUrl)
            .headers(h -> h.setBasicAuth(clientId, clientSecret))
            .bodyValue(body)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .retrieve()
            .bodyToMono(Map.class)
            .block(Duration.ofSeconds(10));

        if (response != null && response.containsKey("access_token")) {
            String token = (String) response.get("access_token");
            Number expiresIn = (Number) response.getOrDefault("expires_in", 3600);
            Duration ttl = Duration.ofSeconds(Math.min(expiresIn.longValue() - 60, TOKEN_CACHE_TTL.getSeconds()));
            redisTemplate.opsForValue().set(cacheKey, token, ttl);
            return "Bearer " + token;
        }

        log.error("Token exchange failed for server {} tenant {}", server.getServerId(), identity.tenantId());
        return null;
    }
}
