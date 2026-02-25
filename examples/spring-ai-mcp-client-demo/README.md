# Spring AI MCP Client Demo

演示如何使用 Spring Security OAuth2 Client 对接 MCP Gateway。

## 核心概念

### Token 自动管理

这个 Demo 的核心是 **Spring Security OAuth2 Client** 自动处理 Token：

```
┌─────────────────────────────────────────────────────────────────┐
│                     Spring AI MCP Client                        │
│                                                                 │
│  ┌─────────────┐    ┌──────────────────────┐    ┌────────────┐ │
│  │ DemoController │ → │ McpGatewayClient     │ → │ WebClient  │ │
│  └─────────────┘    └──────────────────────┘    └─────┬──────┘ │
│                                                       │        │
│                     ┌─────────────────────────────────┘        │
│                     ▼                                          │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ ServerOAuth2AuthorizedClientExchangeFilterFunction       │  │
│  │                                                          │  │
│  │ 自动处理：                                                 │  │
│  │ 1. 首次请求 → 调用 /oauth2/token 获取 Token               │  │
│  │ 2. Token 有效 → 添加 Authorization: Bearer xxx            │  │
│  │ 3. Token 过期 → 自动刷新，然后重试请求                      │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                        MCP Gateway                              │
│                                                                 │
│  /oauth2/token  ←  获取 Token                                   │
│  /mcp/v1        ←  MCP JSON-RPC 端点                            │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 关键配置

#### 1. application.yml - OAuth2 Client 配置

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          mcp-gateway:
            authorization-grant-type: client_credentials  # M2M 模式
            client-id: demo-agent
            client-secret: demo-secret
            scope: mcp:tools-basic
            provider: mcp-gateway
        provider:
          mcp-gateway:
            token-uri: http://localhost:8080/oauth2/token
```

#### 2. OAuth2ClientConfig.java - WebClient 配置

```java
@Bean
public WebClient mcpWebClient(ReactiveOAuth2AuthorizedClientManager authorizedClientManager) {
    // 创建 OAuth2 过滤器 - 自动处理 Token
    ServerOAuth2AuthorizedClientExchangeFilterFunction oauth2Filter =
            new ServerOAuth2AuthorizedClientExchangeFilterFunction(authorizedClientManager);
    
    oauth2Filter.setDefaultClientRegistrationId("mcp-gateway");

    return WebClient.builder()
            .filter(oauth2Filter)  // 关键：添加 OAuth2 过滤器
            .build();
}
```

#### 3. McpGatewayClient.java - 调用 MCP

```java
// 不需要手动处理 Token！WebClient 的 OAuth2 过滤器会自动处理
return mcpWebClient.post()
        .uri(gatewayUrl + "/mcp/v1")
        .bodyValue(request)
        .retrieve()
        .bodyToMono(Map.class);
```

## 运行步骤

### 1. 启动 MCP Gateway

```bash
cd agent-platform-gateway
mvn spring-boot:run
```

### 2. 在 Gateway 中注册 Client

```bash
# 调用 Gateway 的管理 API 注册一个 Client
curl -X POST http://localhost:8080/api/v1/admin/oauth2/clients \
  -H "Content-Type: application/json" \
  -d '{
    "clientId": "demo-agent",
    "clientSecret": "demo-secret",
    "scopes": ["mcp:tools-basic"]
  }'
```

### 3. 启动 Demo 应用

```bash
cd examples/spring-ai-mcp-client-demo
mvn spring-boot:run
```

### 4. 测试

```bash
# 初始化 MCP 连接
curl http://localhost:8081/demo/initialize

# 获取工具列表
curl http://localhost:8081/demo/tools

# 调用工具
curl -X POST http://localhost:8081/demo/tools/call \
  -H "Content-Type: application/json" \
  -d '{"name": "weather", "arguments": {"city": "Beijing"}}'
```

## 项目结构

```
spring-ai-mcp-client-demo/
├── pom.xml                              # Maven 依赖
├── README.md                            # 本文档
└── src/main/
    ├── java/com/agentplatform/examples/
    │   ├── McpClientDemoApplication.java    # 启动类
    │   ├── config/
    │   │   ├── OAuth2ClientConfig.java      # OAuth2 配置（核心）
    │   │   └── SecurityConfig.java          # 安全配置
    │   ├── controller/
    │   │   └── DemoController.java          # REST API
    │   └── service/
    │       └── McpGatewayClient.java        # MCP 调用服务
    └── resources/
        └── application.yml                  # 配置文件
```

## 为什么不需要手动处理 Token？

Spring Security OAuth2 Client 的 `ServerOAuth2AuthorizedClientExchangeFilterFunction` 会：

1. **首次请求**：检测到没有 Token，自动调用 `/oauth2/token` 获取
2. **后续请求**：检测到 Token 有效，自动添加到请求头
3. **Token 过期**：检测到 Token 过期，自动刷新后重试请求

你只需要配置好 `client-id`、`client-secret`、`token-uri`，剩下的全部自动处理。

## 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `MCP_GATEWAY_URL` | `http://localhost:8080` | MCP Gateway 地址 |
| `MCP_CLIENT_ID` | `demo-agent` | OAuth2 Client ID |
| `MCP_CLIENT_SECRET` | `demo-secret` | OAuth2 Client Secret |
