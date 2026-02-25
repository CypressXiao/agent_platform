# exception — 异常处理

本包定义了平台统一的错误码体系和全局异常处理器。

## 文件清单

| 文件 | 说明 |
|------|------|
| `McpErrorCode.java` | 错误码枚举，定义了 25 种错误码，按模块分组：AuthN/AuthZ、Grant、Upstream、Tool、Governance、General、v2 Workflow/Planner/Memory/LLM。每个错误码包含 `code`（字符串标识）、`defaultMessage`（默认消息）、`httpStatus`（HTTP 状态码） |
| `McpException.java` | 平台统一运行时异常，携带 `McpErrorCode` 和可选的详细信息。提供三种构造方式：仅错误码、错误码+详情、错误码+详情+原因链 |
| `GlobalExceptionHandler.java` | `@RestControllerAdvice` 全局异常处理器，捕获 `McpException` 并转换为标准化 JSON 响应（包含 `error`、`code`、`message`、`timestamp` 字段），同时处理通用异常兜底 |

## 错误码分组

| 分组 | 错误码示例 | HTTP 状态码 |
|------|-----------|------------|
| 认证授权 | `UNAUTHORIZED`, `FORBIDDEN_SCOPE`, `FORBIDDEN_POLICY` | 401, 403 |
| 授权共享 | `FORBIDDEN_NO_GRANT`, `GRANT_EXPIRED`, `GRANT_REVOKED` | 403 |
| 上游服务 | `UPSTREAM_UNHEALTHY`, `UPSTREAM_TIMEOUT` | 502, 504 |
| 治理 | `RATE_LIMITED`, `CIRCUIT_OPEN` | 429, 503 |
| v2 子系统 | `GRAPH_NOT_FOUND`, `PLAN_NOT_FOUND`, `LLM_QUOTA_EXCEEDED` 等 | 404, 403, 429, 502 |
