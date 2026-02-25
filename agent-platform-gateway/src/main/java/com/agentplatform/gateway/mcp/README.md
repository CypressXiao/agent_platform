# mcp — MCP 协议层

本包实现 MCP（Model Context Protocol）协议的服务端，将标准 MCP 协议请求桥接到平台内部路由引擎。

## 文件清单

| 文件 | 说明 |
|------|------|
| `McpProtocolHandler.java` | JSON-RPC 2.0 端点控制器（`POST /mcp/v1`）。处理四种 MCP 方法：`initialize`（返回服务器能力）、`ping`（心跳）、`tools/list`（委托 ToolAggregator）、`tools/call`（委托 ToolDispatcher）。统一处理 JSON-RPC 错误格式（`-32601` 方法不存在、`-32603` 内部错误等） |
| `McpServerConfig.java` | MCP 服务器配置类。创建 `DynamicToolCallbackProvider` Bean，注入 `ToolAggregator` 和 `ToolDispatcher`，配置调用方身份提取器（`Supplier<CallerIdentity>`） |
| `DynamicToolCallbackProvider.java` | 动态工具回调提供器。实现 Spring AI 的 `ToolCallbackProvider` 接口，根据当前调用方身份动态返回可见工具的 `ToolCallback` 列表。每次调用 `getToolCallbacks()` 时实时查询 `ToolAggregator` |
| `GatewayToolCallback.java` | 单个工具的回调实现。实现 Spring AI 的 `ToolCallback` 接口，将 MCP 协议的 `tool.call` 请求解析为 `Map<String, Object>` 参数，委托 `ToolDispatcher.dispatch()` 执行，并将结果序列化为 JSON 字符串返回 |

## 协议流程

```
MCP Client (Agent)
  → POST /mcp/v1  (JSON-RPC 2.0)
  → McpProtocolHandler 解析方法名
    → "initialize"  → 返回 serverInfo + capabilities
    → "ping"        → 返回 {}
    → "tools/list"  → ToolAggregator.listTools() → ToolView 列表
    → "tools/call"  → ToolDispatcher.dispatch() → 执行结果
  → 封装为 JSON-RPC 2.0 Response 返回
```

## Spring AI MCP 集成

- `DynamicToolCallbackProvider` 同时支持 Spring AI MCP Server 框架的自动注册
- `GatewayToolCallback` 桥接 Spring AI `ToolCallback` 接口与平台 `ToolDispatcher`
- 支持 MCP 2025-11-25 协议版本
