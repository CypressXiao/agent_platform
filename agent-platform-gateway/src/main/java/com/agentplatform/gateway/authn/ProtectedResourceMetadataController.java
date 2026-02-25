package com.agentplatform.gateway.authn;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class ProtectedResourceMetadataController {

    @Value("${mcp.gateway.canonical-uri:https://mcp-gateway.example.com}")
    private String canonicalUri;

    @Value("${mcp.gateway.auth-mode:sso}")
    private String authMode;

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:#{null}}")
    private String issuerUri;

    @GetMapping("/.well-known/oauth-protected-resource")
    public Map<String, Object> metadata() {
        // local 模式：授权服务器就是网关自身；sso 模式：指向外部 SSO
        String authServer = "local".equals(authMode) ? canonicalUri
            : (issuerUri != null ? issuerUri : canonicalUri);

        return Map.of(
            "resource", canonicalUri,
            "authorization_servers", List.of(authServer),
            "scopes_supported", List.of("mcp:tools-basic", "mcp:tools-admin"),
            "bearer_methods_supported", List.of("header")
        );
    }
}
