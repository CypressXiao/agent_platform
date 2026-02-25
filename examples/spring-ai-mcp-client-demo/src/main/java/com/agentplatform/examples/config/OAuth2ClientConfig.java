package com.agentplatform.examples.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ClientCredentialsReactiveOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.InMemoryReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServerOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * OAuth2 Client 配置。
 * 
 * 核心功能：
 * 1. 配置 ReactiveOAuth2AuthorizedClientManager 管理 Token 生命周期
 * 2. 配置 WebClient 自动携带 Bearer Token
 * 3. Token 过期时自动刷新（Spring Security 内置支持）
 */
@Configuration
public class OAuth2ClientConfig {

    /**
     * 创建 OAuth2 授权客户端服务。
     * 用于存储已授权的客户端信息（包括 Token）。
     */
    @Bean
    public ReactiveOAuth2AuthorizedClientService authorizedClientService(
            ReactiveClientRegistrationRepository clientRegistrationRepository) {
        return new InMemoryReactiveOAuth2AuthorizedClientService(clientRegistrationRepository);
    }

    /**
     * 创建 OAuth2 授权客户端管理器。
     * 
     * 这个管理器负责：
     * 1. 首次请求时自动获取 Token
     * 2. Token 过期时自动刷新（关键：需要配置 ClientCredentialsReactiveOAuth2AuthorizedClientProvider）
     * 3. 管理 Token 的生命周期
     * 
     * 重要：必须设置 authorizedClientProvider，否则不会自动刷新过期 Token！
     */
    @Bean
    public ReactiveOAuth2AuthorizedClientManager authorizedClientManager(
            ReactiveClientRegistrationRepository clientRegistrationRepository,
            ReactiveOAuth2AuthorizedClientService authorizedClientService) {
        
        // 关键：配置 client_credentials 授权提供者
        // 这个 Provider 会在 Token 过期时自动重新获取
        ClientCredentialsReactiveOAuth2AuthorizedClientProvider clientCredentialsProvider =
                new ClientCredentialsReactiveOAuth2AuthorizedClientProvider();
        
        AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(
                        clientRegistrationRepository, authorizedClientService);
        
        // 设置授权提供者 - 这是自动刷新 Token 的关键！
        manager.setAuthorizedClientProvider(clientCredentialsProvider);
        
        return manager;
    }

    /**
     * 创建带 OAuth2 认证的 WebClient。
     * 
     * 关键点：
     * - ServerOAuth2AuthorizedClientExchangeFilterFunction 会自动：
     *   1. 在请求前检查是否有有效 Token
     *   2. 如果没有或已过期，自动调用 /oauth2/token 获取新 Token
     *   3. 将 Token 添加到请求的 Authorization 头
     * 
     * 使用时只需要：
     * webClient.get().uri("/mcp/v1").retrieve()...
     * 不需要手动处理 Token！
     */
    @Bean
    public WebClient mcpWebClient(ReactiveOAuth2AuthorizedClientManager authorizedClientManager) {
        // 创建 OAuth2 过滤器
        ServerOAuth2AuthorizedClientExchangeFilterFunction oauth2Filter =
                new ServerOAuth2AuthorizedClientExchangeFilterFunction(authorizedClientManager);
        
        // 设置默认使用的 OAuth2 Client Registration
        // "mcp-gateway" 对应 application.yml 中的 registration 名称
        oauth2Filter.setDefaultClientRegistrationId("mcp-gateway");

        return WebClient.builder()
                .filter(oauth2Filter)
                .build();
    }
}
