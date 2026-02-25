package com.agentplatform.gateway.config;

import com.agentplatform.common.model.CallerIdentity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 */
@Configuration
@Profile("dev")
@EnableWebSecurity
public class DevSecurityConfig {

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
