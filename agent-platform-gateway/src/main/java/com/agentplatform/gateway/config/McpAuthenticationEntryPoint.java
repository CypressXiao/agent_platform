package com.agentplatform.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

public class McpAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final String canonicalUri;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public McpAuthenticationEntryPoint(String canonicalUri) {
        this.canonicalUri = canonicalUri;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setHeader("WWW-Authenticate",
            "Bearer resource_metadata=\"" + canonicalUri + "/.well-known/oauth-protected-resource\"");

        Map<String, Object> body = Map.of(
            "error", Map.of(
                "code", "UNAUTHORIZED",
                "message", "Authentication required. See WWW-Authenticate header for resource metadata.",
                "timestamp", Instant.now().toString()
            )
        );
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
