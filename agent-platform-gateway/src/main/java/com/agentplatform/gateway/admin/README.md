# admin — 管理 API

本包提供平台管理员使用的 REST API 控制器，用于管理平台核心资源。

## 文件清单

| 文件 | 端点前缀 | 说明 |
|------|---------|------|
| `TenantAdminController.java` | `/api/v1/admin/tenants` | 租户管理：创建租户、查询租户列表、获取租户详情、更新租户信息 |
| `ServerAdminController.java` | `/api/v1/admin/servers` | 上游服务器管理：注册 MCP Server（`POST /mcp`）、注册 REST API（`POST /rest`）、按租户查询服务器列表、获取服务器详情、注销服务器 |
| `ToolAdminController.java` | `/api/v1/admin/tools` | 工具管理：按所属租户查询、按来源查询、获取工具详情、启用/禁用工具 |
| `GrantAdminController.java` | `/api/v1/admin/grants` | 授权管理：创建跨租户 Grant、按授权方/被授权方查询、获取 Grant 详情、撤销 Grant |
| `AuditAdminController.java` | `/api/v1/admin/audit` | 审计查询：按租户+时间范围分页查询审计日志、按 Trace ID 查询完整调用链 |

## 权限要求

所有 Admin API 端点需要 `SCOPE_admin` 权限（在 `SecurityConfig` 中配置）。开发模式下（`DevSecurityConfig`）自动放行。

## 接口风格

- RESTful 风格，使用标准 HTTP 方法（GET/POST/PUT/DELETE）
- 返回 `ResponseEntity<T>` 带正确的 HTTP 状态码
- 列表查询支持分页（`page`、`size` 参数）
- 错误通过 `GlobalExceptionHandler` 统一处理
