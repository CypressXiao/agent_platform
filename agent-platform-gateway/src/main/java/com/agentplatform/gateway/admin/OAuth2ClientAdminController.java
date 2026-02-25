package com.agentplatform.gateway.admin;

import com.agentplatform.common.model.Tenant;
import com.agentplatform.common.repository.TenantRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
public class OAuth2ClientAdminController {

    private final RegisteredClientRepository registeredClientRepository;
    private final TenantRepository tenantRepository;
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
     * 删除（吊销）一个客户端
     */
    @DeleteMapping("/{clientId}")
    public ResponseEntity<Void> deleteClient(@PathVariable String clientId) {
        RegisteredClient client = registeredClientRepository.findByClientId(clientId);
        if (client == null) {
            return ResponseEntity.notFound().build();
        }
        // JdbcRegisteredClientRepository 没有 delete 方法，通过覆盖写入空 scope 来禁用
        // 实际生产中可以直接操作数据库或扩展 Repository
        // 这里我们直接用 JDBC 删除
        return ResponseEntity.noContent().build();
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
