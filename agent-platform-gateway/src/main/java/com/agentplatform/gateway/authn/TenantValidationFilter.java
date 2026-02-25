package com.agentplatform.gateway.authn;

import com.agentplatform.common.repository.TenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/**
 * 租户验证过滤器。
 * 在 JWT 验签通过后，从 token 中提取 client_id 作为租户标识，
 * 校验该租户是否存在且状态为 active。
 * 不合法的租户直接返回 403。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TenantValidationFilter extends OncePerRequestFilter {

    private final TenantRepository tenantRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        // 未认证的请求跳过（由 Spring Security 自身处理）
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Jwt jwt)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 用 client_id 作为租户标识
        String clientId = jwt.getClaimAsString("client_id");
        if (clientId == null) {
            clientId = jwt.getSubject();
        }

        if (clientId == null || clientId.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        // 校验 client_id 对应的租户是否存在且状态为 active
        boolean valid = tenantRepository.findByTidAndStatus(clientId, "active").isPresent();

        if (!valid) {
            log.warn("Tenant validation failed: client_id={}, uri={}", clientId, request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            Map<String, Object> body = Map.of(
                "error", Map.of(
                    "code", "FORBIDDEN_TENANT",
                    "message", "Client '" + clientId + "' is not registered as an active tenant",
                    "timestamp", Instant.now().toString()
                )
            );
            objectMapper.writeValue(response.getOutputStream(), body);
            return;
        }

        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // 公开端点不需要租户校验
        return path.startsWith("/.well-known")
            || path.startsWith("/actuator")
            || path.startsWith("/swagger-ui")
            || path.startsWith("/v3/api-docs")
            || path.startsWith("/oauth2");
    }
}
