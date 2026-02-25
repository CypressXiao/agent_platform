# model — 核心数据模型

本包定义了平台所有核心 JPA 实体，映射到 PostgreSQL 数据库表。

## 文件清单

| 文件 | 说明 |
|------|------|
| `CallerIdentity.java` | 调用方身份记录（record），包含 `tenantId`、`clientId`、`scopes`、`token`、`traceId`，贯穿整个请求链路 |
| `Tenant.java` | 租户实体，对应 `tenant` 表。包含租户名称、联系人、配额配置（JSONB）、状态 |
| `UpstreamServer.java` | 上游服务器实体，对应 `upstream_server` 表。支持 MCP / REST 两种类型，记录连接信息、认证配置、健康状态 |
| `Tool.java` | 工具实体，对应 `tool` 表。统一抽象三种来源（`builtin` / `upstream_mcp` / `upstream_rest`），记录输入 Schema、执行映射、响应映射 |
| `Grant.java` | 授权实体，对应 `grant_record` 表。实现跨租户 Tool 共享，包含授权方/被授权方、Tool 列表、Scope 列表、约束条件、过期/撤销机制 |
| `AuditLog.java` | 审计日志实体，对应 `audit_log` 表。记录每次 Tool 调用的完整上下文：调用方、工具、结果、延迟、Trace ID、Grant ID |
| `Policy.java` | 策略实体，对应 `policy` 表。存储 OPA 策略定义，支持按租户/全局维度配置访问控制规则 |

## 设计要点

- 所有实体使用 `String` 类型主键（UUID），便于分布式环境
- JSONB 字段通过 Hypersistence Utils 的 `@Type(JsonType.class)` 映射为 `Map<String, Object>` 或 `List<String>`
- `Grant` 表名为 `grant_record`（避免 SQL 保留字冲突）
- `CallerIdentity` 是 Java `record`，不可变，用于在请求链路中传递调用方上下文
