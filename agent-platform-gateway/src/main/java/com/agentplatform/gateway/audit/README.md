# audit — 审计与可观测

本包负责全量审计日志记录和指标采集。

## 文件清单

| 文件 | 说明 |
|------|------|
| `AuditLogService.java` | 审计日志服务。异步记录每次 Tool 调用的完整上下文（调用方、工具、结果、延迟、Trace ID、Grant ID），持久化到 `audit_log` 表。同时通过 Micrometer 采集指标：`mcp.tool.calls`（调用计数，按工具名和结果标签区分）、`mcp.tool.latency`（调用延迟分布）。集成 OpenTelemetry Tracer 自动关联 Trace ID |

## 审计字段

每条审计记录包含：
- **who**: `actorTid`（调用方租户）、`clientId`（客户端 ID）
- **what**: `toolName`（工具名）、`action`（操作类型）
- **result**: `resultCode`（SUCCESS / 错误码）、`latencyMs`（延迟毫秒）
- **context**: `traceId`（链路追踪 ID）、`grantId`（跨租户授权 ID）
- **when**: `timestamp`（时间戳）

## 可观测集成

- **Metrics**: Prometheus 格式，通过 `/actuator/prometheus` 暴露
- **Tracing**: OpenTelemetry OTLP 导出，与 Jaeger/Zipkin 集成
- **Logging**: 结构化日志，包含 Trace ID 便于关联
