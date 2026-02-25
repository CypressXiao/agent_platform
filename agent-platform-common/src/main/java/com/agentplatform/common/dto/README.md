# dto — 数据传输对象

本包定义了 Admin API 的请求/响应 DTO，用于接口参数校验和数据传输。

## 文件清单

| 文件 | 说明 |
|------|------|
| `RegisterMcpServerRequest.java` | 注册上游 MCP Server 的请求体。包含 `serverId`、`baseUrl`、`transport`（stdio/sse）、`authProfile`（认证配置）、`ownerTid`、`tags` |
| `RegisterRestApiRequest.java` | 注册上游 REST API 的请求体。包含 `serverId`、`baseUrl`、`authProfile`、`apiSpec`（OpenAPI 规范）、`healthEndpoint`、`tools`（手动定义的工具列表），内嵌 `RestToolDefinition` 子类 |
| `CreateGrantRequest.java` | 创建跨租户授权的请求体。包含 `grantorTid`（授权方）、`granteeTid`（被授权方）、`tools`（工具 ID 列表）、`scopes`、`constraints`、`expiresAt` |
| `AuditQueryRequest.java` | 审计日志查询的请求参数。支持按 `tenantId`、`toolName`、`traceId`、时间范围过滤，带分页参数 |

## 设计要点

- 使用 Lombok `@Data` / `@Builder` 简化样板代码
- `RegisterRestApiRequest` 内嵌 `RestToolDefinition`，支持在注册 REST API 时同时定义工具的输入 Schema、执行映射（HTTP 方法/路径）、响应映射
- DTO 与实体解耦，Admin Controller 负责 DTO → Entity 转换
