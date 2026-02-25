package com.agentplatform.gateway.config;

import com.agentplatform.common.model.CallerIdentity;
import com.agentplatform.gateway.authn.TenantValidationFilter;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${mcp.gateway.auth-mode:sso}")
    private String authMode;

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:#{null}}")
    private String jwkSetUri;

    @Value("${mcp.gateway.canonical-uri:https://mcp-gateway.example.com}")
    private String canonicalUri;

    @Value("${mcp.gateway.cors.allowed-origins:}")
    private String allowedOrigins;

    @Autowired(required = false)
    private JWKSource<SecurityContext> jwkSource;

    @Autowired
    private TenantValidationFilter tenantValidationFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/.well-known/**").permitAll()
                .requestMatchers("/actuator/health/**").permitAll()
                .requestMatchers("/actuator/prometheus").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .requestMatchers("/oauth2/**").permitAll()
                .requestMatchers("/mcp/v1/**").authenticated()
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/admin/audit/**").hasAnyAuthority("SCOPE_mcp:tools-admin", "SCOPE_mcp:audit-read")
                .requestMatchers("/api/v1/admin/**").hasAuthority("SCOPE_mcp:tools-admin")
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> {
                if ("local".equals(authMode) && jwkSource != null) {
                    oauth2.jwt(jwt -> jwt
                        .decoder(localJwtDecoder())
                        .jwtAuthenticationConverter(jwtAuthenticationConverter())
                    );
                } else if (jwkSetUri != null) {
                    oauth2.jwt(jwt -> jwt
                        .jwkSetUri(jwkSetUri)
                        .jwtAuthenticationConverter(jwtAuthenticationConverter())
                    );
                } else {
                    oauth2.jwt(jwt -> jwt
                        .jwtAuthenticationConverter(jwtAuthenticationConverter())
                    );
                }
            })
            .addFilterAfter(tenantValidationFilter, BearerTokenAuthenticationFilter.class)
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new McpAuthenticationEntryPoint(canonicalUri))
            );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        if (allowedOrigins != null && !allowedOrigins.isBlank()) {
            config.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
        } else {
            config.setAllowedOrigins(List.of()); // deny all cross-origin by default
        }
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-ID"));
        config.setExposedHeaders(List.of("WWW-Authenticate", "X-Request-ID"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private JwtDecoder localJwtDecoder() {
        // 本地模式：使用网关自身的 JWKS 端点验证 token
        return NimbusJwtDecoder.withJwkSetUri(canonicalUri + "/oauth2/jwks")
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .build();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<String> scopes = jwt.getClaimAsStringList("scope");
            if (scopes == null) {
                String scopeStr = jwt.getClaimAsString("scope");
                scopes = scopeStr != null ? Arrays.asList(scopeStr.split(" ")) : List.of();
            }
            return scopes.stream()
                .map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope))
                .collect(Collectors.toList());
        });
        return converter;
    }

    @Bean
    public Supplier<CallerIdentity> callerIdentityExtractor() {
        return () -> {
            var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
            if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
                return new CallerIdentity("anonymous", "anonymous", Set.of(), "none", null);
            }
            List<String> scopeList = jwt.getClaimAsStringList("scope");
            Set<String> scopes = scopeList != null ? new HashSet<>(scopeList) : Set.of();
            String clientId = jwt.getClaimAsString("client_id") != null ? jwt.getClaimAsString("client_id") : jwt.getSubject();
            return new CallerIdentity(
                clientId,
                clientId,
                scopes,
                jwt.getId(),
                jwt.getExpiresAt()
            );
        };
    }
}
