# registry — 注册中心

本包负责上游服务器注册、Tool 发现、内置 Tool 管理和健康检查。

## 文件清单

| 文件 | 说明 |
|------|------|
| `UpstreamServerService.java` | 上游服务器注册服务。支持注册 MCP Server（自动发现 Tool）和 REST API（手动定义 Tool），以及注销服务器（级联禁用关联 Tool） |
| `McpToolDiscoveryService.java` | MCP Tool 发现服务。连接上游 MCP Server，调用 `tools/list` 获取工具列表，转换为平台 `Tool` 实体并持久化 |
| `BuiltinToolHandler.java` | 内置 Tool 处理器接口。定义 `toolName()`、`description()`、`inputSchema()`、`execute()` 四个方法，所有内置 Tool 实现此接口 |
| `BuiltinToolRegistry.java` | 内置 Tool 注册表。应用启动时自动收集所有 `BuiltinToolHandler` 实现，注册到 `tool` 表（`source_type = 'builtin'`，`owner_tid = 'system'`），并提供按名称查找 Handler 的能力 |
| `HealthCheckScheduler.java` | 健康检查调度器。定时（默认 60s）检查所有上游服务器的健康状态，更新 `health_status` 和 `last_health_check` 字段。MCP 类型检查 `/mcp` 端点，REST 类型检查配置的 `health_endpoint` |

## 注册流程

```
Admin 调用注册 API
  → UpstreamServerService 保存服务器记录
  → McpToolDiscoveryService 发现工具（MCP）/ 手动创建工具（REST）
  → Tool 记录持久化到数据库
  → BuiltinToolRegistry 启动时自动注册内置工具
  → HealthCheckScheduler 定时巡检
```
