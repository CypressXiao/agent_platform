package com.agentplatform.gateway.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI 3.0 配置
 * 访问地址: /swagger-ui.html 或 /swagger-ui/index.html
 * API 文档: /v3/api-docs
 */
@Configuration
public class OpenApiConfig {

    @Value("${server.port:8080}")
    private int serverPort;

    @Bean
    public OpenAPI agentPlatformOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("Agent Platform Gateway API")
                .description("""
                    Agent 能力网关 API 文档
                    
                    ## 概述
                    本平台为 Agent 服务提供统一的能力入口，包括：
                    - **LLM 调用**: 多供应商路由、配额管理
                    - **向量存储/RAG**: Embedding、存储、检索
                    - **Prompt 管理**: 模板存储、版本控制、渲染
                    - **Memory 服务**: 短期/长期/结构化记忆
                    
                    ## 认证
                    所有 API 都需要 Bearer Token 认证（JWT）。
                    
                    ## 多租户
                    数据按 tenant_id 隔离，tenant_id 从 JWT 中提取。
                    """)
                .version("1.0.0")
                .contact(new Contact()
                    .name("Agent Platform Team")
                    .email("agent-platform@example.com"))
                .license(new License()
                    .name("Apache 2.0")
                    .url("https://www.apache.org/licenses/LICENSE-2.0")))
            .servers(List.of(
                new Server()
                    .url("http://localhost:" + serverPort)
                    .description("本地开发环境")))
            .tags(List.of(
                new Tag().name("MCP").description("MCP 协议入口 (JSON-RPC 2.0)"),
                new Tag().name("LLM").description("LLM 路由服务"),
                new Tag().name("Vector").description("向量存储/检索服务"),
                new Tag().name("Prompt").description("Prompt 模板管理"),
                new Tag().name("Memory").description("记忆服务"),
                new Tag().name("Admin").description("平台管理 API (需要管理员权限)")))
            .components(new Components()
                .addSecuritySchemes("bearer-jwt", new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("JWT Bearer Token")))
            .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"));
    }
}
