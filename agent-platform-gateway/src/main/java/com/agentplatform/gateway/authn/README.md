# authn — 认证层

本包实现 MCP 协议的认证相关端点。

## 文件清单

| 文件 | 说明 |
|------|------|
| `ProtectedResourceMetadataController.java` | 实现 RFC 9728 Protected Resource Metadata 端点（`/.well-known/oauth-protected-resource`）。返回资源服务器元数据，包括授权服务器地址、支持的 Scope 列表（`mcp:tools-basic`、`mcp:tools-admin`、`mcp:grants-manage`）、Bearer Token 方式等，供 MCP Client 自动发现认证配置 |

## 协议说明

MCP 2025-11-25 规范要求 MCP Server 作为 OAuth 2.1 Protected Resource，通过此端点告知 Client：
1. 去哪里获取 Token（`authorization_servers`）
2. 需要哪些权限（`scopes_supported`）
3. 如何传递 Token（`bearer_methods_supported`）
