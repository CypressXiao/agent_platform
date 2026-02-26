# Tracing 模块

## 概述
调用链追踪服务，为 Agent 服务提供请求追踪、性能分析和调试能力。

## 核心能力
1. **调用链追踪** - 跨服务调用链路追踪
2. **Span 管理** - 创建和管理 Span
3. **上下文传播** - 自动传播 TraceId/SpanId
4. **性能指标** - 记录延迟、状态等指标

## 技术栈
- **Tracing**: Micrometer Tracing + OpenTelemetry
- **导出**: OTLP (可配置 Jaeger/Zipkin)

## 配置
```yaml
management:
  tracing:
    sampling:
      probability: 1.0
  otlp:
    tracing:
      endpoint: ${OTLP_ENDPOINT:http://localhost:4318/v1/traces}
```

## 自动追踪
- MCP 工具调用
- LLM 调用
- 向量检索
- 上游服务调用

## 使用方式
```java
@Autowired
private TracingService tracingService;

// 创建 Span
try (var span = tracingService.startSpan("my-operation")) {
    span.tag("key", "value");
    // 业务逻辑
}
```
