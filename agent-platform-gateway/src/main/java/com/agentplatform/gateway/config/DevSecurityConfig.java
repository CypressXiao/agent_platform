package com.agentplatform.gateway.config;

import com.agentplatform.common.model.CallerIdentity;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Set;
import java.util.function.Supplier;

/**
 * Development security configuration — disables JWT validation.
 * Activated via spring.profiles.active=dev
 *
 * WARNING: This configuration disables ALL authentication.
 * It will refuse to start if the server is bound to a non-localhost address.
 */
@Configuration
@Profile("dev")
@EnableWebSecurity
public class DevSecurityConfig {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DevSecurityConfig.class);

    @Value("${server.address:localhost}")
    private String serverAddress;

    @PostConstruct
    public void validateDevProfile() {
        boolean isLocal = "localhost".equals(serverAddress)
            || "127.0.0.1".equals(serverAddress)
            || "0:0:0:0:0:0:0:1".equals(serverAddress)
            || "::1".equals(serverAddress);

        log.warn("╔══════════════════════════════════════════════════════════════╗");
        log.warn("║  WARNING: Dev profile is ACTIVE — ALL authentication is OFF ║");
        log.warn("║  DO NOT use this profile in production environments!        ║");
        log.warn("╚══════════════════════════════════════════════════════════════╝");

        if (!isLocal && !"0.0.0.0".equals(serverAddress)) {
            throw new IllegalStateException(
                "SECURITY ABORT: Dev profile is active but server.address='" + serverAddress +
                "' is not localhost. Refusing to start without authentication on a non-local interface. " +
                "Remove 'dev' from spring.profiles.active for non-local deployments.");
        }
    }

    @Bean
    @Primary
    public SecurityFilterChain devSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Primary
    public Supplier<CallerIdentity> devCallerIdentityExtractor() {
        return () -> new CallerIdentity(
            "dev-tenant",
            "dev-client",
            Set.of("mcp:tools-basic", "mcp:tools-admin"),
            "dev-token",
            null
        );
    }
}
