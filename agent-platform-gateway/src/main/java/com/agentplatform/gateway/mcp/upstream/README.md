# upstream — 上游代理

本包负责将 Tool 调用代理到上游 MCP Server 或 REST API，并处理凭证交换。

## 文件清单

| 文件 | 说明 |
|------|------|
| `McpProxyService.java` | MCP 代理服务。将 `tools/call` 请求封装为 JSON-RPC 2.0 格式，通过 WebClient 转发到上游 MCP Server。自动处理 Token Exchange 获取上游凭证，支持超时和错误处理 |
| `RestProxyService.java` | REST 代理服务。根据 Tool 的 `executionMapping`（HTTP 方法、路径模板、参数映射）构建 HTTP 请求，转发到上游 REST API。支持路径参数替换、Query 参数、请求体映射，以及响应字段提取（`responseMapping`） |
| `TokenExchangeService.java` | Token 交换服务。根据上游服务器的 `authProfile` 配置，获取访问上游的凭证。支持四种模式：`api_key`（从 Vault 获取）、`basic`（用户名密码）、`client_credentials`（OAuth2 客户端凭证流）、`token_exchange`（RFC 8693 Token Exchange） |
| `VaultService.java` | HashiCorp Vault 集成。安全存储和获取上游服务器的凭证（API Key、密码等）。支持通过配置开关禁用（`vault.enabled=false` 时返回占位符），避免开发环境依赖 Vault |

## 代理流程

```
ToolDispatcher 路由到上游
  → TokenExchangeService 获取上游凭证
    → VaultService 从 Vault 读取密钥（如需）
  → McpProxyService / RestProxyService 转发请求
  → 解析响应并返回
```

## 安全原则

- **MUST NOT Token Passthrough**: 绝不透传调用方 Token 到上游，始终通过 Token Exchange 获取独立凭证
- 上游凭证存储在 Vault 中，运行时按需获取，不持久化到数据库
