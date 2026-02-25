package com.agentplatform.examples;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring AI MCP Client Demo 应用。
 * 
 * 演示如何使用 Spring AI MCP + Spring Security OAuth2 Client 对接 MCP Gateway。
 * 
 * 主要功能：
 * 1. OAuth2 Client Credentials 认证（自动获取和刷新 Token）
 * 2. 调用 MCP Gateway 的 tools/list 获取工具列表
 * 3. 调用 MCP Gateway 的 tools/call 执行工具
 */
@SpringBootApplication
public class McpClientDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpClientDemoApplication.class, args);
    }
}
