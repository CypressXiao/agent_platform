# builtin — 内置工具

本包实现平台自带的内置 Tool，以 `system` 租户身份注册，对所有租户可见。

## 文件清单

| 文件 | 工具名 | 说明 |
|------|--------|------|
| `EchoToolHandler.java` | `echo` | 回显工具，用于测试和调试。接收 `message` 参数，返回包含原始消息、调用方租户 ID、客户端 ID、时间戳的 JSON 对象 |
| `HealthCheckToolHandler.java` | `health_check` | 健康检查工具。查询当前租户下所有上游服务器的健康状态，支持可选的 `server_id` 参数过滤特定服务器。返回每个服务器的 ID、类型、URL、健康状态、最后检查时间 |

## 扩展方式

新增内置工具只需：
1. 创建类实现 `BuiltinToolHandler` 接口
2. 添加 `@Component` 注解
3. 实现 `toolName()`、`description()`、`inputSchema()`、`execute()` 四个方法

`BuiltinToolRegistry` 会在启动时自动发现并注册到 `tool` 表。

## v2 内置工具

v2 子系统的内置工具（如 `workflow_run`、`plan_create`、`llm_chat` 等）定义在各自的 `v2/` 子包中，通过 `@ConditionalOnProperty` 按需激活，但同样实现 `BuiltinToolHandler` 接口，遵循相同的注册机制。
