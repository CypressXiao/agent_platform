# Agent Platform 差距分析与优化需求

## 概述

本文档基于"Server-first Agent 中台标准清单"对 Agent Platform 进行全面审计，识别当前能力缺口并提供优先级落地建议。

## 审计结果

### ✅ 已具备能力（较完整）

#### 协议与路由
- **MCP 端点与 SSE 推送**: `/mcp/v1`、`/mcp/v1/sse`，支持 `initialize/tools/list/tools/call`
- **工具路由中枢**: `ToolDispatcher`（Scope 校验、OPA 策略、跨租户 Grant、治理拦截、审计）
- **上游管理**: MCP/REST 上游注册与代理、健康检查

#### 治理与安全
- **限流**: Redis 滑动窗口（`RateLimitService`）
- **熔断**: Resilience4j（`CircuitBreakerService`）
- **权限**: Spring Security + OAuth2（支持本地授权服务器）、多租户 `CallerIdentity`、OPA 策略（`authz.rego`）
- **审计**: `AuditLogService` 已接入工具调用链路

#### 能力层
- **LLM 路由**: OpenAI 兼容上游调用、Fallback、用量与成本核算（`LlmRouterService` + `LlmUsageRecord` + `LlmTenantQuota`）
- **向量/RAG**: `VectorStoreService` 抽象、多模型独立 VectorStore、检索过滤、RAG 三种模式（Naive/Advanced/Graph）与组件（重排、补全、改写、GraphStore）
- **Prompt**: 模板存储、版本化、渲染（Mustache 简化版）
- **Memory**: 短期 Redis、长期（Mem0 开关 + PG 回退）、结构化实体记忆，过期清理任务

#### 观测
- **链路追踪**: Micrometer Tracing + AOP 切面对 Tool/LLM/Vector 关键路径打点（`TracingService`、`ToolTracingAspect`）

#### 文档与 API
- **完整文档**: `docs/capabilities.md`、`api-guide.md`、`MCP-Gateway-Integration-Guide.md`
- **接入示例**: 包含管理与接入的完整示例

### 🌟 亮点
- 工具治理面（Scope/OPA/Grant/限流/熔断/审计）在 MCP 主链路已成体系
- RAG 与 Chunking 能力偏工程化，`DocumentChunker` 策略丰富且对 SOP 友好

## 主要缺口与改进需求

### 🔴 P0 - 第一优先（1-2周内）

#### 1. 工具参数 JSON Schema 校验
**现状**: `tools/list` 暴露 `inputSchema`，未见统一的 Schema 校验器  
**需求**: 在 `ToolDispatcher` 入参侧执行 JSON Schema 校验，将 4xx 错误前移

**技术方案**:
```java
@Component
public class JsonSchemaValidator {
    private final JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    
    public void validate(String toolName, Map<String, Object> arguments, JsonSchema schema) {
        // 执行校验，抛出 ValidationException
    }
}
```

### 🟡 P1 - 第二优先（2-4周内）

#### 2. 统一事件模型与回放
**现状**: 有 Tracing/Audit，但未见"Run/Step/ToolCall"统一事件模型与结构化日志  
**需求**: 
- 抽象 `RunId/StepId/ParentId`、统一事件载荷
- 落地 OpenTelemetry 导出或接 Langfuse
- 形成"观察-评测-迭代"闭环（按运维需求启动）

**技术方案**:
```java
public class ToolCallEvent {
    private String runId;
    private String stepId;
    private String parentStepId;
    private String toolName;
    private Map<String, Object> arguments;
    private Object result;
    private long latencyMs;
    private String status; // SUCCESS/ERROR/TIMEOUT
}
```

#### 3. Prompt 策略化与 A/B
**现状**: 有版本化，但无灰度发布、按租户/任务/模型的策略路由  
**需求**:
- 引入 Prompt 策略层与流量分配
- 串接评测指标做自动优选

#### 4. 统一重试/超时策略
**现状**: LLM 调用 `block(60s)` 固定超时；有熔断但未见统一的重试/超时策略  
**需求**: 集中治理（resilience4j Retry/Timeout/Bulkhead），按租户/工具/上游可配置

#### 5. 评测与质量治理
**现状**: 未见 RAG/对话评测流水线（Ragas/Promptfoo/TruLens）  
**需求**: 加"离线评测 + 在线 A/B"两路，形成 PR/灰度门禁指标

### 🟢 P2 - 第三优先（4-8周内）

#### 6. Webhooks/事件集成
**现状**: 有 SSE 通知；未见 Webhook/CloudEvents 出站  
**需求**: 新增工具变更、审计事件、配额预警等 Webhook

#### 7. 数据治理/保留策略
**现状**: Memory 有清理；向量、审计、用量记录未见保留/归档策略  
**需求**: 按租户/类型 TTL 与归档导出能力

#### 8. 内容安全/隐私治理
**现状**: 未见 PII/DLP、越狱/有害输出防护  
**需求**: 在 LLM 出入站与工具出入站挂载安全过滤、脱敏与审计

#### 9. 审计覆盖面扩展
**现状**: `ToolDispatcher` 路径有审计；`/api/v1/vectors`、`/api/v1/rag` 等 REST 能力未见一致的审计记录  
**需求**: 统一审计切面/拦截器，将 REST 能力也纳入"谁在何时以何参数调用了什么"

## 小快改（本周可落地）

### 1. ChunkingController AGENTIC 策略暴露
**问题**: `AGENTIC` 策略在 `DocumentChunker` 存在，但 `ChunkingController` 的策略列表与 `ChunkingRequest` 注释未暴露该策略  
**解决方案**:
```java
// 在 ChunkingController 中添加
@GetMapping("/strategies")
public List<ChunkingStrategy> getStrategies() {
    return Arrays.asList(
        // 现有策略...
        ChunkingStrategy.AGENTIC  // 添加此策略
    );
}

// 在 ChunkingRequest 中添加
@Schema(description = "Agentic 模式使用的模型", example = "gpt-4")
private String agenticModel;
```

### 2. MCP 协议版本统一
**问题**: MCP `initialize` 的 `protocolVersion`（代码：2025-03-26）与文档（2024-11-05）不一致  
**解决方案**: 统一使用官方最新版本 2024-11-05

### 3. REST 能力审计拦截器
**问题**: REST 能力（vectors/rag/chunking）缺少统一审计  
**解决方案**:
```java
@Aspect
@Component
public class RestApiAuditAspect {
    @Around("@annotation(org.springframework.web.bind.annotation.*Mapping)")
    public Object auditRestCall(ProceedingJoinPoint joinPoint) {
        // 统一审计逻辑
    }
}
```

### 4. JSON Schema 校验器骨架
**问题**: 工具参数缺少校验  
**解决方案**: 在 `ToolDispatcher` 中集成校验器骨架

## 实施计划

### Phase 1: 基础能力补齐（Week 1-2）✅ 已完成
- [x] ChunkingController AGENTIC 策略暴露
- [x] MCP 协议版本统一
- [x] REST 能力审计拦截器
- [x] JSON Schema 校验器骨架

### Phase 2: 核心能力建设（Week 3-6）✅ 已完成
- [x] 统一事件模型与回放
- [x] Prompt 策略化与 A/B
- [x] 统一重试/超时策略
- [x] 评测与质量治理

### Phase 3: 高级能力完善（Week 7-10）✅ 已完成
- [x] Webhooks/事件集成
- [x] 数据治理/保留策略
- [x] 内容安全/隐私治理

## 技术债务

### 1. 配置管理
- 部分硬编码配置需要外部化（如超时时间、重试次数）
- 缺少配置热更新机制

### 2. 错误处理
- 错误码体系需要标准化
- 缺少统一的异常处理机制

### 3. 性能优化
- 向量检索性能优化（索引策略、缓存机制）
- LLM 调用批处理优化

## 监控指标

### 核心指标
- **可用性**: 99.9% SLA
- **延迟**: P95 < 2s（工具调用），P95 < 10s（RAG 查询）
- **错误率**: < 0.1%
- **成本控制**: 单租户月成本 < 预算阈值

### 业务指标
- **工具调用成功率**: > 95%
- **RAG 检索命中率**: > 80%
- **Prompt 渲染成功率**: > 99%
- **审计完整性**: 100%

## 风险评估

### 高风险
- **数据安全**: 多租户数据隔离需要严格验证
- **性能瓶颈**: 高并发下的向量检索可能成为瓶颈
- **依赖风险**: 外部 LLM 服务可用性影响

### 中风险
- **兼容性**: MCP 协议版本升级可能影响现有客户端
- **扩展性**: 当前架构在超大租户量下的扩展性需要验证

## 成功标准

### 功能完整性
- [ ] 完整的 MCP 协议支持与内部 RESTful API
- [ ] 完整的工具治理体系
- [ ] 端到端的观测与评测能力

### 性能指标
- [ ] 满足上述监控指标要求
- [ ] 支持 1000+ 并发租户
- [ ] 99.9% 服务可用性

### 运维能力
- [ ] 完整的监控告警体系
- [ ] 自动化部署与回滚
- [ ] 灾难恢复预案

---

**文档版本**: v1.0  
**最后更新**: 2025-02-28  
**负责人**: Platform Team  
**审核人**: Architecture Committee
