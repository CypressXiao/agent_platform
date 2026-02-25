package com.agentplatform.gateway.admin;

import com.agentplatform.common.model.Tenant;
import com.agentplatform.common.repository.TenantRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.*;

/**
 * OAuth2 客户端管理 API（仅 local 模式启用）。
 * 管理员通过此接口注册/查询/删除可以申请 token 的客户端。
 */
@RestController
@RequestMapping("/api/v1/admin/oauth2-clients")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mcp.gateway.auth-mode", havingValue = "local")
@Slf4j
public class OAuth2ClientAdminController {

    private final RegisteredClientRepository registeredClientRepository;
    private final TenantRepository tenantRepository;
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    /**
     * 注册一个新的 OAuth2 客户端
     */
    @PostMapping
    public ResponseEntity<ClientResponse> registerClient(@Valid @RequestBody CreateClientRequest request) {
        // 生成随机 secret 返回给调用方（明文只展示一次）
        String rawSecret = UUID.randomUUID().toString().replace("-", "");

        RegisteredClient.Builder builder = RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId(request.getClientId())
            .clientSecret(passwordEncoder.encode(rawSecret))
            .clientName(request.getClientName())
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS);

        // 设置允许的 scope
        if (request.getScopes() != null && !request.getScopes().isEmpty()) {
            request.getScopes().forEach(builder::scope);
        } else {
            builder.scope("mcp:tools-basic");
        }

        // 设置 token 有效期
        long ttl = request.getTokenTtlMinutes() != null ? request.getTokenTtlMinutes() : 60;
        builder.tokenSettings(TokenSettings.builder()
            .accessTokenTimeToLive(Duration.ofMinutes(ttl))
            .build());

        builder.clientSettings(ClientSettings.builder()
            .requireAuthorizationConsent(false)
            .build());

        RegisteredClient registeredClient = builder.build();
        registeredClientRepository.save(registeredClient);

        // 自动创建对应的租户记录（client_id = tenant tid）
        if (tenantRepository.findById(request.getClientId()).isEmpty()) {
            Tenant tenant = Tenant.builder()
                .tid(request.getClientId())
                .name(request.getClientName())
                .status("active")
                .build();
            tenantRepository.save(tenant);
        }

        ClientResponse response = new ClientResponse();
        response.setId(registeredClient.getId());
        response.setClientId(registeredClient.getClientId());
        response.setClientSecret(rawSecret);
        response.setClientName(registeredClient.getClientName());
        response.setScopes(new ArrayList<>(registeredClient.getScopes()));
        response.setTokenTtlMinutes(ttl);
        response.setMessage("请妥善保存 clientSecret，此值仅展示一次");

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 根据 clientId 查询客户端信息
     */
    @GetMapping("/{clientId}")
    public ResponseEntity<ClientInfoResponse> getClient(@PathVariable String clientId) {
        RegisteredClient client = registeredClientRepository.findByClientId(clientId);
        if (client == null) {
            return ResponseEntity.notFound().build();
        }

        ClientInfoResponse response = new ClientInfoResponse();
        response.setId(client.getId());
        response.setClientId(client.getClientId());
        response.setClientName(client.getClientName());
        response.setScopes(new ArrayList<>(client.getScopes()));
        response.setGrantTypes(client.getAuthorizationGrantTypes().stream()
            .map(AuthorizationGrantType::getValue)
            .toList());
        response.setClientIdIssuedAt(client.getClientIdIssuedAt() != null
            ? client.getClientIdIssuedAt().toString() : null);

        return ResponseEntity.ok(response);
    }

    /**
     * 删除（吊销）一个客户端。
     * 同时删除该客户端已签发的所有授权记录和同意记录，并停用对应租户。
     */
    @DeleteMapping("/{clientId}")
    public ResponseEntity<Void> deleteClient(@PathVariable String clientId) {
        RegisteredClient client = registeredClientRepository.findByClientId(clientId);
        if (client == null) {
            return ResponseEntity.notFound().build();
        }

        String registeredClientId = client.getId();

        // 删除已签发的授权记录
        jdbcTemplate.update("DELETE FROM oauth2_authorization WHERE registered_client_id = ?", registeredClientId);
        // 删除授权同意记录
        jdbcTemplate.update("DELETE FROM oauth2_authorization_consent WHERE registered_client_id = ?", registeredClientId);
        // 删除客户端注册记录
        jdbcTemplate.update("DELETE FROM oauth2_registered_client WHERE id = ?", registeredClientId);

        // 停用对应租户
        tenantRepository.findById(clientId).ifPresent(tenant -> {
            tenant.setStatus("inactive");
            tenantRepository.save(tenant);
        });

        log.info("Deleted OAuth2 client and deactivated tenant: {}", clientId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 轮换客户端密钥。生成新 secret 并返回（明文仅展示一次），旧 secret 立即失效。
     */
    @PostMapping("/{clientId}/rotate-secret")
    public ResponseEntity<ClientResponse> rotateClientSecret(@PathVariable String clientId) {
        RegisteredClient existing = registeredClientRepository.findByClientId(clientId);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }

        String rawSecret = UUID.randomUUID().toString().replace("-", "");

        // 用原有配置重建客户端，仅替换 secret
        RegisteredClient.Builder builder = RegisteredClient.withId(existing.getId())
            .clientId(existing.getClientId())
            .clientSecret(passwordEncoder.encode(rawSecret))
            .clientName(existing.getClientName())
            .clientIdIssuedAt(existing.getClientIdIssuedAt());

        existing.getClientAuthenticationMethods().forEach(builder::clientAuthenticationMethod);
        existing.getAuthorizationGrantTypes().forEach(builder::authorizationGrantType);
        existing.getScopes().forEach(builder::scope);
        if (existing.getTokenSettings() != null) {
            builder.tokenSettings(existing.getTokenSettings());
        }
        if (existing.getClientSettings() != null) {
            builder.clientSettings(existing.getClientSettings());
        }

        RegisteredClient updated = builder.build();
        registeredClientRepository.save(updated);

        log.info("Rotated client secret for: {}", clientId);

        ClientResponse response = new ClientResponse();
        response.setId(updated.getId());
        response.setClientId(updated.getClientId());
        response.setClientSecret(rawSecret);
        response.setClientName(updated.getClientName());
        response.setScopes(new ArrayList<>(updated.getScopes()));
        response.setMessage("密钥已轮换，请妥善保存新 clientSecret，此值仅展示一次");

        return ResponseEntity.ok(response);
    }

    // ---- Request / Response DTOs ----

    @Data
    public static class CreateClientRequest {
        @NotBlank(message = "clientId 不能为空")
        private String clientId;

        @NotBlank(message = "clientName 不能为空")
        private String clientName;

        private List<String> scopes;

        private Long tokenTtlMinutes;
    }

    @Data
    public static class ClientResponse {
        private String id;
        private String clientId;
        private String clientSecret;
        private String clientName;
        private List<String> scopes;
        private Long tokenTtlMinutes;
        private String message;
    }

    @Data
    public static class ClientInfoResponse {
        private String id;
        private String clientId;
        private String clientName;
        private List<String> scopes;
        private List<String> grantTypes;
        private String clientIdIssuedAt;
    }
}
